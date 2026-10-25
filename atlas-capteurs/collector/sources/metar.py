"""Relevés des aéroports (METAR) du monde entier, fichier de cache du NWS américain (Aviation Weather Center)."""
import csv
import gzip
import json
import re

from .. import outils
from ..outils import ErreurSource
from .base import Resultat, dans_intervalle

ID = 'metar'
NOM = 'Aéroports (METAR), NOAA Aviation Weather Center'
SITE = 'https://aviationweather.gov/data/api/'
COUCHES = ['pression', 'temperature']
DESCRIPTION = 'Pression ramenée au niveau de la mer (QNH) et température relevées par les aéroports, environ 5 000 dans le monde.'
LICENCE = {'nom': 'Domaine public (NWS, NOAA)', 'url': 'https://www.weather.gov/disclaimer', 'commercial': True}
ATTRIBUTION = 'NOAA, National Weather Service, Aviation Weather Center'
INTERVALLE_MIN = 30
PERIME_APRES_MIN = 120
DELAI_MAX_S = 120
CLES = []

URL = 'https://aviationweather.gov/data/cache/metars.cache.csv.gz'
URL_STATIONS = 'https://aviationweather.gov/data/cache/stations.cache.json.gz'
AGE_MAX_H = 3
INHG_EN_HPA = 33.8639
CHAMPS = ['code', 'lat', 'lon', 'temps', 'qnh_hpa', 'temperature_c']
MINIMUM = 500


def decompresser(contenu):
    try:
        return gzip.decompress(contenu)
    except OSError:     # déjà décompressé en route
        return contenu


def pression_metar(brut, altim_inhg):
    """QNH en hPa : le groupe Q1013 est exact ; sinon A2992 (pouces de mercure) converti."""
    corps = (brut or '').split(' RMK', 1)[0]
    m = re.search(r'\bQ(\d{4})\b', corps)
    if m:
        return float(m.group(1))
    m = re.search(r'\bA(\d{4})\b', corps)
    if m:
        return round(int(m.group(1)) / 100 * INHG_EN_HPA, 1)
    v = outils.nombre(altim_inhg)
    return round(v * INHG_EN_HPA, 1) if v else None


def lire_csv(texte, moment):
    lignes = texte.splitlines()
    debut = next((i for i, l in enumerate(lignes) if l.startswith('raw_text')), None)
    if debut is None:
        raise ErreurSource('format inattendu : entête « raw_text » absente')
    lecteur = csv.reader(lignes[debut:])
    entete = next(lecteur)
    col = {}
    for i, nom in enumerate(entete):
        col.setdefault(nom, i)      # certaines colonnes sont en double : on garde la première
    besoin = ['raw_text', 'station_id', 'observation_time', 'latitude', 'longitude', 'temp_c', 'altim_in_hg']
    if any(b not in col for b in besoin):
        raise ErreurSource('format inattendu : colonnes manquantes')
    limite = outils.epoch(moment) - AGE_MAX_H * 3600
    stations = {}
    for c in lecteur:
        if len(c) <= max(col[b] for b in besoin):
            continue
        code = c[col['station_id']].strip()
        quand = outils.lire_iso(c[col['observation_time']])
        lat, lon = outils.nombre(c[col['latitude']]), outils.nombre(c[col['longitude']])
        if not code or quand is None or not outils.coordonnees_valides(lat, lon):
            continue
        t = outils.epoch(quand)
        if t < limite or t > outils.epoch(moment) + 900:
            continue
        qnh = pression_metar(c[col['raw_text']], c[col['altim_in_hg']])
        temp = outils.nombre(c[col['temp_c']])
        qnh = qnh if dans_intervalle(qnh, 870, 1085) else None
        temp = temp if dans_intervalle(temp, -80, 60) else None
        if qnh is None and temp is None:
            continue
        ancien = stations.get(code)
        if ancien is None or ancien[3] < t:
            stations[code] = [code, round(lat, 3), round(lon, 3), t, qnh, temp]
    return sorted(stations.values())


def noms_stations(ctx):
    """Noms lisibles des aéroports, rafraîchis une fois par jour."""
    ref = ctx.precedent('ref_metar_noms.json')
    if ref and ctx.moment.timestamp() - ref.get('maj', 0) < 86400:
        return ref
    try:
        r = ctx.http.get(URL_STATIONS, delai=90)
        liste = json.loads(decompresser(r.content))
        noms = {s['icaoId']: outils.texte_propre(s.get('site')) for s in liste if isinstance(s, dict) and s.get('icaoId')}
        if len(noms) > 1000 or ref is None:
            return {'maj': outils.epoch(ctx.moment), 'noms': noms}
    except (ErreurSource, ValueError, KeyError, TypeError):
        pass
    return ref or {'maj': 0, 'noms': {}}


def collecter(ctx):
    r = ctx.http.get(URL, delai=90)
    texte = decompresser(r.content).decode('utf-8', 'replace')
    stations = lire_csv(texte, ctx.moment)
    if len(stations) < MINIMUM:
        raise ErreurSource('seulement %d aéroports reçus : réponse jugée anormale' % len(stations))
    ref = noms_stations(ctx)
    presents = {s[0] for s in stations}
    noms = {k: v for k, v in (ref.get('noms') or {}).items() if k in presents}
    contenu = {'source': ID, 'champs': CHAMPS, 'stations': stations, 'noms': noms}
    return Resultat({'metar.json': contenu, 'ref_metar_noms.json': ref}, nb=len(stations), periode='3 dernières heures')
