"""Séismes de l'EMSC (Centre sismologique euro-méditerranéen), service FDSN « event », format texte."""
import datetime as dt

from .. import outils
from ..outils import ErreurSource
from .base import Resultat

ID = 'emsc'
NOM = 'EMSC-CSEM SeismicPortal'
SITE = 'https://www.seismicportal.eu'
COUCHES = ['seismes']
DESCRIPTION = 'Séismes des 7 derniers jours : magnitude 2,5 et plus dans le monde, 1,5 et plus en Europe et autour de la Méditerranée.'
LICENCE = {'nom': 'CC BY 4.0', 'url': 'https://creativecommons.org/licenses/by/4.0/deed.fr', 'commercial': True}
ATTRIBUTION = 'Credit: EMSC-CSEM SeismicPortal, https://www.seismicportal.eu'
INTERVALLE_MIN = 10
PERIME_APRES_MIN = 45
DELAI_MAX_S = 120
CLES = []

URL = 'https://seismicportal.eu/fdsnws/event/1/query'
JOURS = 7
ZONES = [
    {'minmag': '2.5'},
    {'minmag': '1.5', 'minlat': '34', 'maxlat': '72', 'minlon': '-25', 'maxlon': '45'},
]
CHAMPS = ['id', 'temps', 'lat', 'lon', 'profondeur_km', 'magnitude', 'type_magnitude', 'region']
MINIMUM = 20   # en dessous, sur 7 jours, la réponse est forcément anormale


def lire_texte(texte):
    """Format texte FDSN : EventID|Time|Latitude|Longitude|Depth/km|Author|Catalog|Contributor|ContributorID|MagType|Magnitude|MagAuthor|EventLocationName."""
    for ligne in texte.splitlines():
        if not ligne.strip() or ligne.startswith('#'):
            continue
        c = [x.strip() for x in ligne.split('|')]
        if len(c) < 13:
            continue
        moment = outils.lire_iso(c[1])
        lat, lon = outils.nombre(c[2]), outils.nombre(c[3])
        mag = outils.nombre(c[10])
        if moment is None or mag is None or not outils.coordonnees_valides(lat, lon):
            continue
        yield [c[0], outils.epoch(moment), round(lat, 3), round(lon, 3),
               outils.arrondi(outils.nombre(c[4]), 1), round(mag, 1), c[9], outils.texte_propre(c[12])]


def collecter(ctx):
    debut = ctx.moment - dt.timedelta(days=JOURS)
    evenements = {}
    for zone in ZONES:
        params = {'format': 'text', 'starttime': debut.strftime('%Y-%m-%dT%H:%M:%S'),
                  'orderby': 'time', 'limit': '10000', **zone}
        r = ctx.http.get(URL, params=params, codes_ok=(200, 204))
        if r.status_code == 204:    # norme FDSN : aucun séisme ne correspond
            continue
        for ev in lire_texte(r.text):
            evenements[ev[0]] = ev
    liste = sorted(evenements.values(), key=lambda e: -e[1])
    if len(liste) < MINIMUM:
        raise ErreurSource('seulement %d séismes reçus sur 7 jours : réponse jugée anormale' % len(liste))
    contenu = {'source': ID, 'jours': JOURS, 'champs': CHAMPS, 'evenements': liste,
               'lien': 'https://www.seismicportal.eu/eventdetails.html?unid='}
    return Resultat({'emsc.json': contenu}, nb=len(liste), periode='%d derniers jours' % JOURS)
