"""Outils de test : un faux client HTTP qui répond avec des fichiers enregistrés, sans réseau."""
import json
from pathlib import Path

from collector import outils
from collector.principal import Contexte

DONNEES = Path(__file__).resolve().parent / 'donnees'


def fichier(nom):
    return (DONNEES / nom).read_bytes()


class FausseReponse:
    def __init__(self, contenu=b'', code=200, entetes=None):
        self.content = contenu if isinstance(contenu, bytes) else contenu.encode('utf-8')
        self.status_code = code
        self.headers = entetes or {}

    @property
    def text(self):
        return self.content.decode('utf-8', 'replace')

    def json(self):
        return json.loads(self.content)

    def iter_content(self, chunk_size=1):
        yield self.content

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FauxHttp:
    """Associe un morceau d'adresse à une réponse. Une adresse inconnue donne une erreur, comme une panne."""

    def __init__(self, routes):
        self.routes = routes
        self.appels = []

    def _trouver(self, url, codes_ok, methode='get'):
        self.appels.append((methode, url))
        for morceau, rep in self.routes.items():
            if morceau in url:
                rep = rep() if callable(rep) else rep
                if rep.status_code not in codes_ok:
                    raise outils.ErreurSource('HTTP %d sur %s' % (rep.status_code, url))
                return rep
        raise outils.ErreurSource('réseau (faux) sur ' + url)

    def get(self, url, params=None, headers=None, delai=60, flux=False, codes_ok=(200,), essais=None):
        return self._trouver(url, codes_ok)

    def head(self, url, headers=None, delai=30, codes_ok=(200,)):
        return self._trouver(url, codes_ok, 'head')

    def post(self, url, params=None, headers=None, data=None, delai=30, codes_ok=(200,)):
        return self._trouver(url, codes_ok, 'post')

    def telecharger(self, url, chemin, delai=120):
        r = self._trouver(url, (200,), 'telecharger')
        Path(chemin).write_bytes(r.content)
        return r.headers


def contexte(http, dossier, moment_iso, env=None, ancien=None):
    return Contexte(http, dossier, outils.lire_iso(moment_iso), ancien or {}, env or {})
