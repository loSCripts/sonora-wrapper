"""Débit mobile mesuré par l'ARCEP (campagnes annuelles « Mon Réseau Mobile », mesures dans les lieux de vie).

Les mesures de « crowdsourcing » fournies par Ookla ne sont pas utilisées : leur licence interdit l'usage commercial.
"""
import csv
import os
import tempfile
from collections import defaultdict

from .. import outils
from ..outils import ErreurSource
from .base import Resultat, dans_intervalle

ID = 'arcep'
NOM = 'ARCEP, Mon Réseau Mobile (mesures de qualité de service)'
SITE = 'https://www.data.gouv.fr/datasets/58c98b1888ee38770950152b'
COUCHES = ['debit']
DESCRIPTION = 'Débit descendant mesuré par l\'ARCEP pour chaque opérateur, dedans et dehors, dans les lieux de vie. Campagne la plus récente de chaque territoire.'
LICENCE = {'nom': 'Licence Ouverte 2.0', 'url': 'https://www.etalab.gouv.fr/licence-ouverte-open-licence/', 'commercial': True}
ATTRIBUTION = 'Arcep, Mon Réseau Mobile'
INTERVALLE_MIN = 7 * 24 * 60
PERIME_APRES_MIN = 21 * 24 * 60
DELAI_MAX_S = 900
CLES = []

URL = 'https://data.arcep.fr/mobile/mesures_qualite_arcep/{annee}/{terr}/{annee}_QoS_{terr}_data_habitations.csv'
TERRITOIRES = [('Metropole', 'Métropole'), ('Outremer', 'Outre-mer')]
ANNEES_EN_ARRIERE = 2
MOIS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre']
MINIMUM = 100


def dernier_fichier(ctx, terr):
    """La campagne la plus récente publiée pour ce territoire (année en cours, puis les précédentes)."""
    for annee in range(ctx.moment.year, ctx.moment.year - ANNEES_EN_ARRIERE - 1, -1):
        url = URL.format(annee=annee, terr=terr)
        r = ctx.http.head(url, codes_ok=(200, 403, 404))
        if r.status_code == 200:
            return annee, url, r.headers.get('Last-Modified', ''), r.headers.get('Content-Length', '')
    return None


def periode_texte(dates):
    """« mai à septembre 2025 » à partir de dates jj/mm/aaaa."""
    mois = sorted({(int(d[6:10]), int(d[3:5])) for d in dates if len(d) >= 10 and d[2] == '/' and d[5] == '/'})
    if not mois:
        return ''
    (a1, m1), (a2, m2) = mois[0], mois[-1]
    if (a1, m1) == (a2, m2):
        return '%s %d' % (MOIS[m1 - 1], a1)
    if a1 == a2:
        return '%s à %s %d' % (MOIS[m1 - 1], MOIS[m2 - 1], a2)
    return '%s %d à %s %d' % (MOIS[m1 - 1], a1, MOIS[m2 - 1], a2)


def agreger(lignes):
    """Médiane du débit descendant par lieu (arrondi à 100 m environ) et par opérateur."""
    groupes = defaultdict(list)
    dates = set()
    operateurs = []
    for x in lignes:
        if (x.get('protocole') or '').strip().upper() != 'DOWNLOAD':
            continue
        debit = outils.nombre(x.get('bitrate_dl'))
        lat, lon = outils.nombre(x.get('latitude_start')), outils.nombre(x.get('longitude_start'))
        op = outils.texte_propre(x.get('operator'))
        if not op or not dans_intervalle(debit, 0, 10000) or not outils.coordonnees_valides(lat, lon):
            continue
        if op not in operateurs:
            operateurs.append(op)
        groupes[(round(lat, 3), round(lon, 3), operateurs.index(op))].append(debit)
        dates.add((x.get('date_start') or '').strip())
    lieux = defaultdict(list)
    for (lat, lon, op), debits in groupes.items():
        lieux[(lat, lon)].append([op, round(outils.mediane(debits)), len(debits)])
    liste = [[lat, lon, sorted(ops)] for (lat, lon), ops in sorted(lieux.items())]
    nb = sum(len(v) for v in groupes.values())
    return operateurs, liste, nb, periode_texte(dates)


def lire_fichier(ctx, url):
    fd, chemin = tempfile.mkstemp(suffix='.csv')
    os.close(fd)
    try:
        ctx.http.telecharger(url, chemin, delai=300)
        with open(chemin, encoding='utf-8-sig', errors='replace', newline='') as f:
            premiere = f.readline()
            f.seek(0)
            separateur = ';' if premiere.count(';') >= premiere.count(',') else ','
            return agreger(csv.DictReader(f, delimiter=separateur))
    finally:
        os.remove(chemin)


def collecter(ctx):
    precedent = ctx.precedent('arcep.json') or {}
    anciens = {b.get('territoire'): b for b in precedent.get('blocs') or []}
    blocs, a_change = [], False
    for terr, nom in TERRITOIRES:
        trouve = dernier_fichier(ctx, terr)
        ancien = anciens.get(nom)
        if trouve is None:
            if ancien:
                blocs.append(ancien)
            continue
        annee, url, modifie, taille = trouve
        if ancien and (ancien.get('url'), ancien.get('modifie'), ancien.get('taille')) == (url, modifie, taille):
            blocs.append(ancien)       # rien de neuf : on ne retélécharge pas
            continue
        operateurs, lieux, nb, periode = lire_fichier(ctx, url)
        if len(lieux) < MINIMUM:
            raise ErreurSource('%s %d : seulement %d lieux mesurés, fichier jugé anormal' % (nom, annee, len(lieux)))
        blocs.append({'territoire': nom, 'annee': annee, 'periode': periode, 'url': url, 'modifie': modifie,
                      'taille': taille, 'mesures': nb, 'operateurs': operateurs, 'lieux': lieux})
        a_change = True
    if not blocs:
        raise ErreurSource('aucune campagne de mesures trouvée')
    texte = ', '.join('%s %d' % (b['territoire'], b['annee']) for b in blocs)
    nb = sum(b.get('mesures', 0) for b in blocs)
    if not a_change and precedent:
        return Resultat({}, nb=nb, periode='campagnes ' + texte, garder=['arcep.json'])
    contenu = {'source': ID, 'unite': 'Mbit/s', 'champs_lieu': ['lat', 'lon', '[operateur, mediane, nombre]'], 'blocs': blocs}
    return Resultat({'arcep.json': contenu}, nb=nb, periode='campagnes ' + texte)
