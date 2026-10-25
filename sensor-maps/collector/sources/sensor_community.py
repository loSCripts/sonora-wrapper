"""Capteurs citoyens Sensor.Community : particules fines, température, pression et bruit, moyennes sur une heure."""
from .. import outils
from ..outils import ErreurSource
from .base import Resultat, dans_intervalle

ID = 'sensor_community'
NOM = 'Sensor.Community (capteurs citoyens)'
SITE = 'https://sensor.community/fr/'
COUCHES = ['air', 'pression', 'temperature', 'bruit']
DESCRIPTION = 'Capteurs installés par des particuliers, surtout en Europe. Matériel bon marché et non calibré : valeurs indicatives.'
LICENCE = {'nom': 'DbCL 1.0', 'url': 'https://opendatacommons.org/licenses/dbcl/1-0/', 'commercial': True}
ATTRIBUTION = 'Sensor.Community, contributeurs du réseau'
INTERVALLE_MIN = 60
PERIME_APRES_MIN = 180
DELAI_MAX_S = 180
CLES = []

URL = 'https://data.sensor.community/static/v2/data.1h.json'
MINIMUM = 500

# Bornes physiques : au-delà, le capteur est saturé ou en panne (le SDS011 plafonne à 999,9 µg/m³)
BORNES = {
    'P2': (0, 500),
    'temperature': (-50, 60),
    'pressure_at_sealevel': (870, 1085),
    'noise_LAeq': (20, 130),
    'noise_LA_min': (10, 130),
    'noise_LA_max': (20, 140),
}


def extraire(liste):
    pm25, temperature, pression, bruit = [], [], [], []
    plus_recent = None
    for x in liste:
        if not isinstance(x, dict):
            continue
        lieu = x.get('location') or {}
        if str(lieu.get('indoor')) == '1':      # capteurs d'intérieur : ils ne disent rien de l'air dehors
            continue
        lat, lon = outils.nombre(lieu.get('latitude')), outils.nombre(lieu.get('longitude'))
        if not outils.coordonnees_valides(lat, lon):
            continue
        lat, lon = round(lat, 3), round(lon, 3)       # environ 100 m : on ne publie pas l'adresse exacte
        v = {}
        for d in x.get('sensordatavalues') or []:
            nom = d.get('value_type')
            val = outils.nombre(d.get('value'))
            if nom == 'pressure_at_sealevel' and val is not None:
                val = val / 100                          # pascals vers hectopascals
            if nom in BORNES and dans_intervalle(val, *BORNES[nom]):
                v[nom] = val
        if not v:
            continue
        quand = outils.lire_iso(x.get('timestamp'))
        if quand and (plus_recent is None or quand > plus_recent):
            plus_recent = quand
        if 'P2' in v:
            pm25.append([lat, lon, round(v['P2'], 1)])
        if 'temperature' in v:
            temperature.append([lat, lon, round(v['temperature'], 1)])
        if 'pressure_at_sealevel' in v:
            pression.append([lat, lon, round(v['pressure_at_sealevel'], 1)])
        if 'noise_LAeq' in v:
            bruit.append([lat, lon, round(v['noise_LAeq'], 1),
                          outils.arrondi(v.get('noise_LA_min'), 1), outils.arrondi(v.get('noise_LA_max'), 1)])
    return pm25, temperature, pression, bruit, plus_recent


def collecter(ctx):
    r = ctx.http.get(URL, delai=150)
    try:
        liste = r.json()
    except ValueError:
        raise ErreurSource('fichier JSON illisible')
    if not isinstance(liste, list):
        raise ErreurSource('format inattendu : liste attendue')
    pm25, temperature, pression, bruit, plus_recent = extraire(liste)
    if len(pm25) < MINIMUM:
        raise ErreurSource('seulement %d capteurs de particules : réponse jugée anormale' % len(pm25))
    contenu = {
        'source': ID, 'moyenne': '1 h', 'jusqu_a': outils.iso(plus_recent or ctx.moment),
        'champs': {'pm25': ['lat', 'lon', 'ug_m3'], 'temperature': ['lat', 'lon', 'c'],
                   'pression': ['lat', 'lon', 'hpa_mer'], 'bruit': ['lat', 'lon', 'laeq', 'la_min', 'la_max']},
        'pm25': pm25, 'temperature': temperature, 'pression': pression, 'bruit': bruit,
    }
    nb = len(pm25) + len(temperature) + len(pression) + len(bruit)
    return Resultat({'sensor_community.json': contenu}, nb=nb, periode='moyenne sur 1 heure')
