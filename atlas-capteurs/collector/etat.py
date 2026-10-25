"""Reprise de l'état : avant chaque passage, on relit ce qui est déjà publié sur le site.

Ainsi, une source en panne garde ses dernières données, sans jamais rien enregistrer dans l'historique du dépôt.
"""
import json
import re
import shutil
from pathlib import Path

from .outils import ErreurSource

NOM_SUR = re.compile(r'^[a-z0-9_]+\.json$')


class EtatIntrouvable(Exception):
    """Le site répond mal : mieux vaut ne rien publier que d'effacer de bonnes données."""


def fichiers_du_manifeste(manifeste):
    noms = set()
    for info in (manifeste.get('sources') or {}).values():
        for nom in info.get('fichiers') or []:
            if isinstance(nom, str) and NOM_SUR.match(nom):
                noms.add(nom)
    return sorted(noms)


def restaurer_depuis_site(http, url_site, dossier_data, anti_cache):
    base = url_site.rstrip('/') + '/data/'
    try:
        r = http.get(base + 'manifest.json', params={'v': anti_cache}, codes_ok=(200, 404))
    except ErreurSource as e:
        raise EtatIntrouvable(str(e))
    if r.status_code == 404:
        print('Aucun manifeste publié : premier passage.')
        return {}
    try:
        manifeste = r.json()
    except ValueError:
        raise EtatIntrouvable('manifest.json illisible')
    for nom in fichiers_du_manifeste(manifeste):
        try:
            f = http.get(base + nom, params={'v': anti_cache}, delai=120, codes_ok=(200, 404))
        except ErreurSource as e:
            raise EtatIntrouvable(str(e))
        if f.status_code == 200:
            (Path(dossier_data) / nom).write_bytes(f.content)
    return manifeste


def restaurer_depuis_dossier(dossier, dossier_data):
    """Pour les essais sur un ordinateur ou un téléphone : l'état vient d'un dossier local."""
    dossier = Path(dossier)
    manifeste_chemin = dossier / 'manifest.json'
    if not manifeste_chemin.exists():
        return {}
    manifeste = json.loads(manifeste_chemin.read_text(encoding='utf-8'))
    for nom in fichiers_du_manifeste(manifeste):
        if (dossier / nom).exists():
            shutil.copyfile(dossier / nom, Path(dossier_data) / nom)
    return manifeste
