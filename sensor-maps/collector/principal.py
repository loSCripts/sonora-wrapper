"""Programme principal : relit l'état publié, lance les sources dont c'est l'heure, écrit le manifeste.

Utilisation (GitHub Actions le fait tout seul) :
    python -m collector --site https://loscripts.github.io/sensor-maps/ --sortie _site
"""
import argparse
import os
import shutil
import signal
import sys
import traceback
from pathlib import Path

from . import outils
from .etat import EtatIntrouvable, restaurer_depuis_dossier, restaurer_depuis_site
from .sources import TOUTES

RACINE = Path(__file__).resolve().parent.parent
VERSION = 1
MARGE_MIN = 3        # le minuteur de GitHub n'est pas précis : on accepte d'avoir 3 minutes d'avance


class Contexte:
    """Ce qu'une source reçoit : le client HTTP, l'heure du passage, son état précédent, les secrets."""

    def __init__(self, http, dossier_data, moment, ancien, env):
        self.http = http
        self.dossier_data = Path(dossier_data)
        self.moment = moment
        self.ancien = ancien
        self.env = env

    def precedent(self, nom_fichier):
        return outils.lire_json(self.dossier_data / nom_fichier)

    def secret(self, nom):
        return (self.env.get(nom) or '').strip()


class DelaiDepasse(Exception):
    pass


def avec_delai(secondes, fonction, *args):
    def alarme(_signum, _frame):
        raise DelaiDepasse('arrêtée au bout de %d s' % secondes)
    precedent = signal.signal(signal.SIGALRM, alarme)
    signal.alarm(secondes)
    try:
        return fonction(*args)
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, precedent)


def cles_manquantes(src, ctx):
    if hasattr(src, 'cles_manquantes'):
        return src.cles_manquantes(ctx)
    manquantes = [c for c in getattr(src, 'CLES', []) if not ctx.secret(c)]
    return 'clé manquante : ' + ', '.join(manquantes) if manquantes else None


def est_due(src, ancien, moment, forcer):
    if forcer:
        return True
    derniere = outils.lire_iso(ancien.get('derniere_tentative'))
    if derniere is None:
        return True
    return (moment - derniere).total_seconds() / 60 >= src.INTERVALLE_MIN - MARGE_MIN


def fiche(src, ancien):
    """Ce que le manifeste dit d'une source : sa description fixe, puis son état repris du passage précédent."""
    return {
        'nom': src.NOM, 'site': src.SITE, 'couches': src.COUCHES, 'description': src.DESCRIPTION,
        'licence': src.LICENCE, 'attribution': src.ATTRIBUTION,
        'intervalle_min': src.INTERVALLE_MIN, 'perime_apres_min': src.PERIME_APRES_MIN,
        'etat': ancien.get('etat', 'jamais'), 'message': ancien.get('message', ''),
        'derniere_tentative': ancien.get('derniere_tentative'), 'derniere_reussite': ancien.get('derniere_reussite'),
        'nb': ancien.get('nb'), 'periode': ancien.get('periode', ''), 'fichiers': list(ancien.get('fichiers') or []),
    }


def message_court(e):
    texte = str(e).strip() or e.__class__.__name__
    return texte[:220]


def passer_source(src, ctx, info, forcer):
    """Lance une source si c'est son heure. Une erreur ici ne touche jamais les autres sources."""
    manque = cles_manquantes(src, ctx)
    if manque:
        info.update(etat='inactif', message=manque)
        return
    if not est_due(src, ctx.ancien, ctx.moment, forcer):
        return
    info['derniere_tentative'] = outils.iso(ctx.moment)
    try:
        res = avec_delai(src.DELAI_MAX_S, src.collecter, ctx)
        for nom, contenu in res.fichiers.items():
            outils.ecrire_json(ctx.dossier_data / nom, contenu)
        info.update(etat='ok', message='', derniere_reussite=outils.iso(ctx.moment),
                    nb=res.nb, periode=res.periode, fichiers=res.noms())
    except Exception as e:      # volontairement large : une source ne doit jamais casser le passage
        info.update(etat='erreur', message=message_court(e))
        print('[%s] erreur : %s' % (src.ID, message_court(e)))
        if not isinstance(e, (outils.ErreurSource, DelaiDepasse)):
            traceback.print_exc()


def resume(manifeste):
    lignes = ['| Source | État | Nombre | Dernière réussite | Message |', '|---|---|---|---|---|']
    for ident, s in manifeste['sources'].items():
        lignes.append('| %s | %s | %s | %s | %s |' % (ident, s['etat'], s.get('nb') if s.get('nb') is not None else '',
                                                    s.get('derniere_reussite') or '', s.get('message') or ''))
    texte = '\n'.join(lignes)
    print(texte)
    chemin = os.environ.get('GITHUB_STEP_SUMMARY')
    if chemin:
        with open(chemin, 'a', encoding='utf-8') as f:
            f.write('### Collecte du ' + manifeste['genere'] + '\n\n' + texte + '\n')


def executer(args, env):
    moment = outils.maintenant()
    sortie = Path(args.sortie)
    if sortie.exists():
        shutil.rmtree(sortie)
    shutil.copytree(RACINE / 'web', sortie)
    data = sortie / 'data'
    data.mkdir(exist_ok=True)

    depot = env.get('GITHUB_REPOSITORY') or 'loSCripts/sensor-maps'
    contact = (env.get('CONTACT') or '').strip() or 'https://github.com/' + depot
    http = outils.Http('SensorMaps/1.0 (+https://github.com/%s; contact: %s)' % (depot, contact))

    if args.etat_local:
        precedent = restaurer_depuis_dossier(args.etat_local, data)
    elif args.site:
        precedent = restaurer_depuis_site(http, args.site, data, anti_cache=outils.epoch(moment))
    else:
        precedent = {}
    anciens = precedent.get('sources') or {}
    forcer = args.forcer or (env.get('FORCER') or '').strip().lower() == 'true'
    seulement = set(args.seulement.split(',')) if args.seulement else None

    manifeste = {'version': VERSION, 'genere': outils.iso(moment), 'sources': {}}
    for src in TOUTES:
        ancien = dict(anciens.get(src.ID) or {})
        info = fiche(src, ancien)
        if seulement is None or src.ID in seulement:
            passer_source(src, Contexte(http, data, moment, ancien, env), info, forcer)
        manifeste['sources'][src.ID] = info
    outils.ecrire_json(data / 'manifest.json', manifeste, lisible=True)
    resume(manifeste)
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(prog='python -m collector', description='Collecte des données publiques de Sensor Maps.')
    p.add_argument('--site', help='adresse du site déjà publié, pour reprendre ses données')
    p.add_argument('--etat-local', help='dossier data/ d\'un passage précédent (essais hors GitHub)')
    p.add_argument('--sortie', default='_site', help='dossier du site à publier (par défaut : _site)')
    p.add_argument('--forcer', action='store_true', help='lancer toutes les sources, même si ce n\'est pas leur heure')
    p.add_argument('--seulement', help='identifiants des sources à lancer, séparés par des virgules')
    args = p.parse_args(argv)
    try:
        return executer(args, os.environ)
    except EtatIntrouvable as e:
        print('Impossible de relire les données déjà publiées (%s). Rien n\'est publié cette fois-ci : '
              'le site garde sa version précédente.' % e, file=sys.stderr)
        return 3
