"""Stations de qualité de l'air du monde entier via OpenAQ (API v3, clé gratuite).

Pas encore testée avec une vraie clé. Seules les stations dont la licence autorise l'usage commercial
sont gardées : chaque lieu OpenAQ indique sa licence, et l'API décrit chaque licence.
"""
import time

from .. import outils
from ..outils import ErreurSource
from .base import Resultat, dans_intervalle

ID = 'openaq'
NOM = 'OpenAQ (stations du monde entier)'
SITE = 'https://openaq.org'
COUCHES = ['air']
DESCRIPTION = 'Particules fines PM2,5 de stations du monde entier. On ne garde que les stations dont la licence permet un usage commercial.'
LICENCE = {'nom': 'Variable selon la station (filtrée)', 'url': 'https://docs.openaq.org/about/terms', 'commercial': True}
ATTRIBUTION = 'OpenAQ et les fournisseurs de chaque station'
INTERVALLE_MIN = 60
PERIME_APRES_MIN = 240
DELAI_MAX_S = 420
CLES = ['OPENAQ_API_KEY']

BASE = 'https://api.openaq.org/v3'
PM25 = 2                # identifiant du paramètre « pm25 » dans OpenAQ (vérifié à chaque passage)
PAR_PAGE = 1000
PAGES_MAX = 60
PAUSE_S = 1.1           # limite gratuite : 60 requêtes par minute
AGE_MAX_H = 3
MINIMUM = 50


def cles_manquantes(ctx):
    return None if ctx.secret('OPENAQ_API_KEY') else 'clé manquante : ajoute le secret OPENAQ_API_KEY (facultatif, voir le README)'


def appel(ctx, chemin, params=None):
    time.sleep(PAUSE_S)
    r = ctx.http.get(BASE + chemin, params=params, headers={'X-API-Key': ctx.secret('OPENAQ_API_KEY')},
                     delai=60, codes_ok=(200, 401, 403))
    if r.status_code in (401, 403):
        raise ErreurSource('clé refusée par OpenAQ (HTTP %d) : vérifie le secret' % r.status_code)
    try:
        return r.json()
    except ValueError:
        raise ErreurSource('réponse JSON illisible sur ' + chemin)


def toutes_les_pages(ctx, chemin, params):
    for page in range(1, PAGES_MAX + 1):
        res = (appel(ctx, chemin, dict(params, limit=PAR_PAGE, page=page)) or {}).get('results') or []
        yield from res
        if len(res) < PAR_PAGE:
            return


def licences_commerciales(liste):
    return {l['id'] for l in liste if isinstance(l, dict) and l.get('commercialUseAllowed') is True and 'id' in l}


def lieux_autorises(lieux, autorisees):
    """Un lieu est gardé si toutes ses licences connues autorisent l'usage commercial."""
    garde = {}
    for lieu in lieux:
        ids = [l.get('id') for l in (lieu.get('licenses') or []) if isinstance(l, dict)]
        if ids and all(i in autorisees for i in ids):
            fournisseur = outils.texte_propre((lieu.get('provider') or {}).get('name'))
            garde[str(lieu.get('id'))] = [outils.texte_propre(lieu.get('name')), fournisseur, 1 if lieu.get('isMonitor') else 0]
    return garde


def reference(ctx):
    """Licences et lieux autorisés, rafraîchis une fois par jour."""
    ref = ctx.precedent('ref_openaq_lieux.json')
    if ref and ctx.moment.timestamp() - ref.get('maj', 0) < 86400:
        return ref
    param = (appel(ctx, '/parameters/%d' % PM25) or {}).get('results') or [{}]
    if (param[0].get('name') or '').lower() != 'pm25':
        raise ErreurSource('le paramètre %d d\'OpenAQ n\'est plus « pm25 »' % PM25)
    autorisees = licences_commerciales(toutes_les_pages(ctx, '/licenses', {}))
    lieux = lieux_autorises(toutes_les_pages(ctx, '/locations', {'parameters_id': PM25}), autorisees)
    if not lieux:
        raise ErreurSource('aucun lieu OpenAQ avec une licence commerciale')
    return {'maj': outils.epoch(ctx.moment), 'lieux': lieux}


def derniers(ctx, lieux):
    limite = outils.epoch(ctx.moment) - AGE_MAX_H * 3600
    sortie = {}
    for m in toutes_les_pages(ctx, '/parameters/%d/latest' % PM25, {}):
        lieu = str(m.get('locationsId'))
        coord = m.get('coordinates') or {}
        lat, lon = outils.nombre(coord.get('latitude')), outils.nombre(coord.get('longitude'))
        quand = outils.lire_iso((m.get('datetime') or {}).get('utc'))
        v = outils.nombre(m.get('value'))
        if lieu not in lieux or quand is None or not outils.coordonnees_valides(lat, lon) or not dans_intervalle(v, 0, 1000):
            continue
        t = outils.epoch(quand)
        if t < limite or (lieu in sortie and sortie[lieu][7] >= t):
            continue
        nom, fournisseur, officiel = lieux[lieu]
        sortie[lieu] = [lieu, nom, fournisseur, officiel, round(lat, 4), round(lon, 4), round(v, 1), t]
    return sorted(sortie.values())


def collecter(ctx):
    ref = reference(ctx)
    stations = derniers(ctx, ref['lieux'])
    if len(stations) < MINIMUM:
        raise ErreurSource('seulement %d stations OpenAQ récentes et autorisées' % len(stations))
    contenu = {'source': ID, 'champs': ['id', 'nom', 'fournisseur', 'officiel', 'lat', 'lon', 'pm25', 'temps'], 'stations': stations}
    return Resultat({'openaq.json': contenu, 'ref_openaq_lieux.json': ref}, nb=len(stations), periode='3 dernières heures')
