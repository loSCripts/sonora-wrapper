"""Stations officielles de qualité de l'air en France (réseau des AASQA, base Geod'air du LCSQA), données horaires « temps réel »."""
import datetime as dt
import io
import xml.etree.ElementTree as ET

from .. import outils
from ..outils import ErreurSource
from .base import Resultat, dans_intervalle

ID = 'lcsqa'
NOM = 'LCSQA et AASQA (stations officielles, France)'
SITE = 'https://www.data.gouv.fr/datasets/donnees-temps-reel-de-mesure-des-concentrations-de-polluants-atmospheriques-reglementes-1'
COUCHES = ['air']
DESCRIPTION = 'Environ 250 stations officielles mesurent les particules fines PM2,5, avec aussi PM10, dioxyde d\'azote et ozone. Moyennes horaires non encore validées.'
LICENCE = {'nom': 'Licence Ouverte 2.0', 'url': 'https://www.etalab.gouv.fr/licence-ouverte-open-licence/', 'commercial': True}
ATTRIBUTION = 'LCSQA, données des AASQA (Geod\'air)'
INTERVALLE_MIN = 60
PERIME_APRES_MIN = 240
DELAI_MAX_S = 300
CLES = []

JEU = '5b98b648634f415309d52a50'
URL_JEU = 'https://www.data.gouv.fr/api/1/datasets/' + JEU + '/'
# Adresse connue le 25/10/2026, utilisée seulement si data.gouv.fr ne répond pas
DATASET_D_SECOURS = ('https://static.data.gouv.fr/resources/donnees-temps-reel-de-mesure-des-concentrations-de-'
                     'polluants-atmospheriques-reglementes-1/20260825-125844/fr-2026-d-07082026-1.xls')
URL_JOUR = ('https://files.data.gouv.fr/ineris/lcsqa/concentrations-de-polluants-atmospheriques-reglementes/'
            'temps-reel/{annee}/FR_E2_{jour}.xml')
POLLUANTS = {'6001': 'pm25', '5': 'pm10', '8': 'no2', '7': 'o3'}
VALIDES = {'1', '2', '3', '4'}        # vocabulaire européen : 1 à 4 valides, -1 et -99 non valides
BORNES = {'pm25': (0, 1000), 'pm10': (0, 2000), 'no2': (0, 2000), 'o3': (0, 1000)}
AGE_MAX_H = 6
MINIMUM = 50
NS = {'om': 'http://www.opengis.net/om/2.0', 'swe': 'http://www.opengis.net/swe/2.0'}
XLINK = '{http://www.w3.org/1999/xlink}href'


# ---------- Positions des stations (fichier « Dataset D », mis à jour quelques fois par an) ----------

def lignes_stations(lignes):
    """Lignes de la feuille AirQualityStations : la première est l'entête."""
    entete = [str(c).strip() for c in lignes[0]]
    i = {nom: entete.index(nom) for nom in ('NatlStationCode', 'Name', 'Municipality', 'Latitude', 'Longitude')}
    ref = {}
    for l in lignes[1:]:
        code = str(l[i['NatlStationCode']]).strip()
        lat, lon = outils.nombre(l[i['Latitude']]), outils.nombre(l[i['Longitude']])
        if code and outils.coordonnees_valides(lat, lon):
            ref[code] = [outils.texte_propre(l[i['Name']]), outils.texte_propre(l[i['Municipality']]),
                         round(lat, 4), round(lon, 4)]
    return ref


def lire_xls(contenu):
    import xlrd     # seulement ici : le reste du collecteur n'en a pas besoin
    classeur = xlrd.open_workbook(file_contents=contenu)
    feuille = classeur.sheet_by_name('AirQualityStations')
    return lignes_stations([feuille.row_values(r) for r in range(feuille.nrows)])


def adresse_dataset_d(ctx):
    try:
        r = ctx.http.get(URL_JEU, delai=60, essais=5)
        ressources = (r.json() or {}).get('resources') or []
    except (ErreurSource, ValueError):
        return DATASET_D_SECOURS
    candidates = [x for x in ressources if 'dataset d' in (x.get('title') or '').lower()
                  and (x.get('url') or '').lower().endswith('.xls')]
    if not candidates:
        return DATASET_D_SECOURS
    return max(candidates, key=lambda x: x.get('last_modified') or '')['url']


def stations(ctx):
    ref = ctx.precedent('ref_lcsqa_stations.json')
    if ref and ctx.moment.timestamp() - ref.get('maj', 0) < 7 * 86400:
        return ref
    try:
        url = adresse_dataset_d(ctx)
        if ref and ref.get('url') == url and ref.get('stations'):
            ref['maj'] = outils.epoch(ctx.moment)
            return ref
        r = ctx.http.get(url, delai=180, essais=5)
        liste = lire_xls(r.content)
        if len(liste) < 100:
            raise ErreurSource('fichier des stations presque vide')
        return {'maj': outils.epoch(ctx.moment), 'url': url, 'stations': liste}
    except ErreurSource:
        if ref:
            return ref      # on garde l'ancienne liste plutôt que de tout perdre
        raise


# ---------- Mesures horaires (fichier XML du jour, heures en UTC) ----------

def lire_xml(contenu):
    """Pour chaque station et polluant suivi : la dernière valeur valide et l'heure de fin de la mesure."""
    dernieres = {}
    for _evt, el in ET.iterparse(io.BytesIO(contenu), events=('end',)):
        if el.tag != '{http://www.opengis.net/om/2.0}OM_Observation':
            continue
        prop = el.find('om:observedProperty', NS)
        code_pol = (prop.get(XLINK) if prop is not None else '').rsplit('/', 1)[-1]
        polluant = POLLUANTS.get(code_pol)
        point = None
        for nv in el.iterfind('om:parameter/om:NamedValue', NS):
            nom, val = nv.find('om:name', NS), nv.find('om:value', NS)
            if nom is not None and val is not None and (nom.get(XLINK) or '').endswith('/SamplingPoint'):
                point = val.get(XLINK) or ''
        valeurs = el.find('.//swe:values', NS)
        if polluant and point and 'SPO-' in point and valeurs is not None and valeurs.text:
            station = point.split('SPO-', 1)[1].split('_', 1)[0]
            for bloc in valeurs.text.split('@@'):
                c = bloc.strip().split(',')
                if len(c) < 5 or c[3].strip() not in VALIDES:
                    continue
                fin, v = outils.lire_iso(c[1]), outils.nombre(c[4])
                if fin is None or not dans_intervalle(v, *BORNES[polluant]):
                    continue
                cle = (station, polluant)
                if cle not in dernieres or dernieres[cle][1] < outils.epoch(fin):
                    dernieres[cle] = [round(v, 1), outils.epoch(fin)]
        el.clear()
    par_station = {}
    for (station, polluant), mesure in dernieres.items():
        par_station.setdefault(station, {})[polluant] = mesure
    return par_station


def mesures(ctx):
    """Le fichier du jour (UTC) ; tôt le matin, on complète avec celui de la veille."""
    resultat = {}
    for decalage in (0, 1):
        jour = (ctx.moment - dt.timedelta(days=decalage)).date()
        url = URL_JOUR.format(annee=jour.year, jour=jour.isoformat())
        r = ctx.http.get(url, delai=180, codes_ok=(200, 404), essais=5)
        if r.status_code == 200:
            for station, pols in lire_xml(r.content).items():
                cible = resultat.setdefault(station, {})
                for pol, mes in pols.items():
                    if pol not in cible or cible[pol][1] < mes[1]:
                        cible[pol] = mes
        if sum(1 for p in resultat.values() if 'pm25' in p) >= MINIMUM:
            break
    return resultat


def collecter(ctx):
    ref = stations(ctx)
    pos = ref.get('stations') or {}
    limite = outils.epoch(ctx.moment) - AGE_MAX_H * 3600
    sortie = []
    for code, pols in sorted(mesures(ctx).items()):
        recents = {p: m for p, m in pols.items() if m[1] >= limite}
        if code in pos and recents:
            nom, commune, lat, lon = pos[code]
            sortie.append([code, nom, commune, lat, lon, recents])
    avec_pm25 = sum(1 for s in sortie if 'pm25' in s[5])
    if avec_pm25 < MINIMUM:
        raise ErreurSource('seulement %d stations avec des PM2,5 récentes : réponse jugée anormale' % avec_pm25)
    contenu = {'source': ID, 'champs': ['code', 'nom', 'commune', 'lat', 'lon', 'mesures'],
               'unites': 'µg/m³, moyenne horaire, heure de fin en secondes UTC', 'stations': sortie}
    return Resultat({'lcsqa.json': contenu, 'ref_lcsqa_stations.json': ref}, nb=len(sortie), periode='moyennes horaires')
