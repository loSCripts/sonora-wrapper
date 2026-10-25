"""Séismes de l'USGS (Institut d'études géologiques des États-Unis), flux GeoJSON de la semaine."""
from .. import outils
from ..outils import ErreurSource
from .base import Resultat

ID = 'usgs'
NOM = 'USGS Earthquake Hazards Program'
SITE = 'https://earthquake.usgs.gov/earthquakes/feed/'
COUCHES = ['seismes']
DESCRIPTION = 'Séismes de magnitude 2,5 et plus dans le monde sur 7 jours. Ceux déjà donnés par l\'EMSC ne sont affichés qu\'une fois.'
LICENCE = {'nom': 'Domaine public (États-Unis)', 'url': 'https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits', 'commercial': True}
ATTRIBUTION = 'U.S. Geological Survey (USGS)'
INTERVALLE_MIN = 10
PERIME_APRES_MIN = 45
DELAI_MAX_S = 90
CLES = []

URL = 'https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_week.geojson'
CHAMPS = ['id', 'temps', 'lat', 'lon', 'profondeur_km', 'magnitude', 'type_magnitude', 'lieu', 'url']
MINIMUM = 10


def lire_geojson(donnees):
    features = donnees.get('features') if isinstance(donnees, dict) else None
    if not isinstance(features, list):
        raise ErreurSource('format inattendu : pas de liste « features »')
    for f in features:
        p = f.get('properties') or {}
        coord = (f.get('geometry') or {}).get('coordinates') or []
        if p.get('type') not in (None, 'earthquake') or len(coord) < 2:
            continue
        lon, lat = outils.nombre(coord[0]), outils.nombre(coord[1])
        prof = outils.nombre(coord[2]) if len(coord) > 2 else None
        mag, temps = outils.nombre(p.get('mag')), outils.nombre(p.get('time'))
        if mag is None or temps is None or not outils.coordonnees_valides(lat, lon):
            continue
        yield [str(f.get('id')), int(temps // 1000), round(lat, 3), round(lon, 3), outils.arrondi(prof, 1),
               round(mag, 1), p.get('magType') or '', outils.texte_propre(p.get('place')), p.get('url') or '']


def collecter(ctx):
    r = ctx.http.get(URL)
    try:
        donnees = r.json()
    except ValueError:
        raise ErreurSource('réponse JSON illisible')
    liste = sorted(lire_geojson(donnees), key=lambda e: -e[1])
    if len(liste) < MINIMUM:
        raise ErreurSource('seulement %d séismes reçus sur 7 jours : réponse jugée anormale' % len(liste))
    contenu = {'source': ID, 'jours': 7, 'champs': CHAMPS, 'evenements': liste}
    return Resultat({'usgs.json': contenu}, nb=len(liste), periode='7 derniers jours')
