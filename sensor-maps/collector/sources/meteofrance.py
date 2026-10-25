"""Observations des stations de Météo-France (API « Package Observations », toutes les stations en un appel).

Pas encore testée avec une vraie clé : le format de réponse est lu avec prudence (liste JSON ou GeoJSON,
unités en kelvins et pascals ou déjà converties). En cas de surprise, le journal de GitHub Actions affiche
le début de la réponse pour qu'on puisse corriger.
"""
import csv
import datetime as dt
import io

from .. import outils
from ..outils import ErreurSource
from .base import Resultat, dans_intervalle

ID = 'meteofrance'
NOM = 'Météo-France, observations des stations'
SITE = 'https://portail-api.meteofrance.fr/web/fr/api/DonneesPubliquesPaquetObservation'
COUCHES = ['pression', 'temperature']
DESCRIPTION = 'Plus de 2 000 stations en France (métropole et outre-mer), relevés toutes les 6 minutes. Seules certaines mesurent la pression.'
LICENCE = {'nom': 'Licence Ouverte 2.0', 'url': 'https://www.etalab.gouv.fr/licence-ouverte-open-licence/', 'commercial': True}
ATTRIBUTION = 'Météo-France'
INTERVALLE_MIN = 30
PERIME_APRES_MIN = 120
DELAI_MAX_S = 180
CLES_UNE_DE = ['METEOFRANCE_API_KEY', 'METEOFRANCE_APPLICATION_ID']

BASE = 'https://public-api.meteofrance.fr/public/DPPaquetObs/v2'
URL_JETON = 'https://portail-api.meteofrance.fr/token'
RETARD_MIN = 12          # les relevés arrivent avec quelques minutes de retard
ESSAIS = 4               # on remonte au plus de 4 pas de 6 minutes
CHAMPS = ['id', 'nom', 'lat', 'lon', 'altitude_m', 'temps', 'pmer_hpa', 'pres_hpa', 'temperature_c', 'humidite_pct', 'vent_ms']
MINIMUM = 100


def cles_manquantes(ctx):
    if any(ctx.secret(c) for c in CLES_UNE_DE):
        return None
    return 'clé manquante : ajoute le secret METEOFRANCE_API_KEY (voir le README)'


def entetes(ctx):
    cle = ctx.secret('METEOFRANCE_API_KEY')
    if cle:
        return {'apikey': cle, 'accept': '*/*'}
    appli = ctx.secret('METEOFRANCE_APPLICATION_ID')
    r = ctx.http.post(URL_JETON, params={'grant_type': 'client_credentials'},
                      headers={'Authorization': 'Basic ' + appli}, codes_ok=(200, 400, 401))
    jeton = (r.json() if r.status_code == 200 else {}).get('access_token')
    if not jeton:
        raise ErreurSource('identifiant d\'application refusé par Météo-France (HTTP %d)' % r.status_code)
    return {'Authorization': 'Bearer ' + jeton, 'accept': '*/*'}


class CleRefusee(ErreurSource):
    pass


def appel(ctx, chemin, params, h):
    r = ctx.http.get(BASE + chemin, params=params, headers=h, delai=90, codes_ok=(200, 204, 400, 401, 403, 404))
    if r.status_code in (401, 403):
        raise CleRefusee('clé refusée par Météo-France (HTTP %d) : vérifie le secret' % r.status_code)
    return r


def premier(d, *cles):
    for c in cles:
        for k in (c, c.lower(), c.upper(), c.capitalize()):
            if k in d and d[k] not in (None, ''):
                return d[k]
    return None


def lire_liste_stations(texte):
    """CSV séparé par des points-virgules : Id_station;Id_omm;Nom_usuel;Latitude;Longitude;Altitude;..."""
    ref = {}
    for ligne in csv.DictReader(io.StringIO(texte.lstrip('﻿')), delimiter=';'):
        ident = (premier(ligne, 'Id_station', 'id_station') or '').strip()
        lat, lon = outils.nombre(premier(ligne, 'Latitude', 'lat')), outils.nombre(premier(ligne, 'Longitude', 'lon'))
        if ident and outils.coordonnees_valides(lat, lon):
            ref[ident] = [outils.texte_propre(premier(ligne, 'Nom_usuel', 'nom')), round(lat, 4), round(lon, 4),
                          outils.nombre(premier(ligne, 'Altitude', 'alt'))]
    return ref


def stations(ctx, h):
    """Liste des stations (noms, positions), rafraîchie une fois par jour."""
    ref = ctx.precedent('ref_meteofrance_stations.json')
    if ref and ctx.moment.timestamp() - ref.get('maj', 0) < 86400:
        return ref
    try:
        r = appel(ctx, '/liste-stations', None, h)
        if r.status_code == 200:
            liste = lire_liste_stations(r.text)
            if len(liste) > 100:
                return {'maj': outils.epoch(ctx.moment), 'stations': liste}
    except CleRefusee:
        raise
    except ErreurSource:
        pass        # sans la liste, les relevés donnent souvent eux-mêmes la position des stations
    return ref or {'maj': 0, 'stations': {}}


def enregistrements(donnees):
    """Accepte une liste d'objets, un GeoJSON, ou un objet qui contient une de ces listes."""
    if isinstance(donnees, dict) and isinstance(donnees.get('features'), list):
        sortie = []
        for f in donnees['features']:
            p = dict(f.get('properties') or {})
            coord = (f.get('geometry') or {}).get('coordinates') or []
            if len(coord) >= 2:
                p.setdefault('lon', coord[0])
                p.setdefault('lat', coord[1])
            sortie.append(p)
        return sortie
    if isinstance(donnees, list):
        return [x for x in donnees if isinstance(x, dict)]
    if isinstance(donnees, dict):
        for v in donnees.values():
            if isinstance(v, list) and v and isinstance(v[0], dict):
                return v
    return []


def en_celsius(t):
    if t is None:
        return None
    return round(t - 273.15, 1) if t > 150 else round(t, 1)


def en_hpa(p):
    if p is None:
        return None
    return round(p / 100, 1) if p > 2000 else round(p, 1)


def convertir(recs, ref, moment_demande):
    sortie = {}
    for r in recs:
        ident = str(premier(r, 'geo_id_insee', 'id_station', 'station_id', 'id') or '').strip()
        infos = ref.get(ident) or ['', None, None, None]
        lat = outils.nombre(premier(r, 'lat', 'latitude')) or infos[1]
        lon = outils.nombre(premier(r, 'lon', 'longitude')) or infos[2]
        if not ident or not outils.coordonnees_valides(lat, lon):
            continue
        quand = outils.lire_iso(premier(r, 'validity_time', 'reference_time', 'date'))
        t = outils.epoch(quand or moment_demande)
        pmer, pres = en_hpa(outils.nombre(premier(r, 'pmer'))), en_hpa(outils.nombre(premier(r, 'pres')))
        temp, hum, vent = en_celsius(outils.nombre(premier(r, 't'))), outils.nombre(premier(r, 'u')), outils.nombre(premier(r, 'ff'))
        pmer = pmer if dans_intervalle(pmer, 870, 1085) else None
        pres = pres if dans_intervalle(pres, 500, 1085) else None
        temp = temp if dans_intervalle(temp, -80, 60) else None
        hum = hum if dans_intervalle(hum, 0, 100) else None
        vent = round(vent, 1) if dans_intervalle(vent, 0, 120) else None
        if pmer is None and temp is None:
            continue
        nom = infos[0] or outils.texte_propre(premier(r, 'name', 'nom'))
        sortie[ident] = [ident, nom, round(lat, 4), round(lon, 4), infos[3], t, pmer, pres, temp, hum, vent]
    return sorted(sortie.values())


def collecter(ctx):
    h = entetes(ctx)
    ref = stations(ctx, h)
    depart = ctx.moment - dt.timedelta(minutes=RETARD_MIN)
    depart = depart.replace(minute=depart.minute - depart.minute % 6, second=0)
    dernier_texte = ''
    for k in range(ESSAIS):
        quand = depart - dt.timedelta(minutes=6 * k)
        r = appel(ctx, '/paquet/stations/infrahoraire-6m', {'date': quand.strftime('%Y-%m-%dT%H:%M:00Z'), 'format': 'json'}, h)
        if r.status_code != 200 or not r.content.strip():
            continue
        dernier_texte = r.text[:300]
        try:
            recs = enregistrements(r.json())
        except ValueError:
            continue
        liste = convertir(recs, ref.get('stations') or {}, quand)
        if len(liste) >= MINIMUM:
            contenu = {'source': ID, 'heure': outils.iso(quand), 'champs': CHAMPS, 'stations': liste}
            return Resultat({'meteofrance.json': contenu, 'ref_meteofrance_stations.json': ref},
                            nb=len(liste), periode='relevés de ' + quand.strftime('%H:%M') + ' UTC')
        print('[meteofrance] réponse reçue mais peu exploitable (%d stations). Début : %r' % (len(liste), dernier_texte))
    raise ErreurSource('aucun relevé exploitable sur les %d derniers pas de 6 minutes' % ESSAIS)
