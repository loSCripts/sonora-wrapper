"""Le programme principal : indépendance des sources, reprise des anciennes données, horaires."""
import json
import tempfile
import time
import types
import unittest
from pathlib import Path
from unittest import mock

from collector import outils, principal
from collector.outils import ErreurSource
from collector.sources.base import Resultat
from collector.tests.aide import FausseReponse, FauxHttp


def fausse_source(ident, collecter, intervalle=10, cles=()):
    return types.SimpleNamespace(
        ID=ident, NOM=ident, SITE='https://exemple.org', COUCHES=['essai'], DESCRIPTION='', LICENCE={},
        ATTRIBUTION='', INTERVALLE_MIN=intervalle, PERIME_APRES_MIN=30, DELAI_MAX_S=1, CLES=list(cles), collecter=collecter)


def ok(ctx):
    return Resultat({'bonne.json': {'valeur': 1}}, nb=1)


def panne(ctx):
    raise ErreurSource('HTTP 503 sur https://exemple.org')


def bogue(ctx):
    return 1 / 0


def lente(ctx):
    time.sleep(3)


class TestPrincipal(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        (base / 'web').mkdir()
        (base / 'web' / 'index.html').write_text('<!doctype html>', encoding='utf-8')
        self.etat = base / 'etat'
        self.etat.mkdir()
        self.sortie = base / 'site'
        self.racine = mock.patch.object(principal, 'RACINE', base)
        self.racine.start()

    def tearDown(self):
        self.racine.stop()
        self.tmp.cleanup()

    def lancer(self, sources, *options, env=None):
        with mock.patch.object(principal, 'TOUTES', sources), mock.patch.dict('os.environ', env or {}, clear=False):
            code = principal.main(['--sortie', str(self.sortie), '--etat-local', str(self.etat), *options])
        manifeste = json.loads((self.sortie / 'data' / 'manifest.json').read_text(encoding='utf-8'))
        return code, manifeste

    def ancien_etat(self, sources):
        outils.ecrire_json(self.etat / 'manifest.json', {'version': 1, 'sources': sources})

    def test_une_panne_ne_touche_pas_les_autres(self):
        outils.ecrire_json(self.etat / 'vieille.json', {'valeur': 'ancienne'})
        self.ancien_etat({'b': {'etat': 'ok', 'derniere_reussite': '2026-10-25T08:00:00Z', 'fichiers': ['vieille.json'], 'nb': 7}})
        code, m = self.lancer([fausse_source('a', ok), fausse_source('b', panne), fausse_source('c', bogue)])
        self.assertEqual(code, 0)
        self.assertEqual(m['sources']['a']['etat'], 'ok')
        self.assertEqual(m['sources']['b']['etat'], 'erreur')
        self.assertIn('503', m['sources']['b']['message'])
        self.assertEqual(m['sources']['b']['derniere_reussite'], '2026-10-25T08:00:00Z')    # on garde la date de la dernière réussite
        self.assertEqual(json.loads((self.sortie / 'data' / 'vieille.json').read_text())['valeur'], 'ancienne')
        self.assertEqual(m['sources']['c']['etat'], 'erreur')
        self.assertTrue((self.sortie / 'data' / 'bonne.json').exists())
        self.assertTrue((self.sortie / 'index.html').exists())

    def test_source_trop_lente_arretee(self):
        code, m = self.lancer([fausse_source('lente', lente), fausse_source('a', ok)])
        self.assertEqual(m['sources']['lente']['etat'], 'erreur')
        self.assertIn('arrêtée', m['sources']['lente']['message'])
        self.assertEqual(m['sources']['a']['etat'], 'ok')

    def test_pas_encore_son_heure(self):
        recent = outils.iso(outils.maintenant())
        self.ancien_etat({'a': {'etat': 'ok', 'derniere_tentative': recent, 'derniere_reussite': recent, 'fichiers': []}})
        appels = []
        code, m = self.lancer([fausse_source('a', lambda ctx: appels.append(1) or ok(ctx), intervalle=60)])
        self.assertEqual(appels, [])
        self.assertEqual(m['sources']['a']['derniere_tentative'], recent)
        code, m = self.lancer([fausse_source('a', lambda ctx: appels.append(1) or ok(ctx), intervalle=60)], '--forcer')
        self.assertEqual(appels, [1])

    def test_cle_absente(self):
        code, m = self.lancer([fausse_source('k', ok, cles=['CLE_QUI_NEXISTE_PAS'])])
        self.assertEqual(m['sources']['k']['etat'], 'inactif')
        self.assertIn('CLE_QUI_NEXISTE_PAS', m['sources']['k']['message'])

    def test_noms_de_fichiers_dangereux_ignores(self):
        from collector.etat import fichiers_du_manifeste
        noms = fichiers_du_manifeste({'sources': {'x': {'fichiers': ['../../secret', 'bon.json', '/etc/passwd', 'a b.json']}}})
        self.assertEqual(noms, ['bon.json'])


class TestReprise(unittest.TestCase):
    def test_premier_passage(self):
        from collector.etat import restaurer_depuis_site
        http = FauxHttp({'manifest.json': FausseReponse(b'', 404)})
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(restaurer_depuis_site(http, 'https://moi.github.io/sensor-maps/', d, 1), {})

    def test_site_injoignable_on_ne_publie_rien(self):
        from collector.etat import EtatIntrouvable, restaurer_depuis_site
        http = FauxHttp({})
        with tempfile.TemporaryDirectory() as d, self.assertRaises(EtatIntrouvable):
            restaurer_depuis_site(http, 'https://moi.github.io/sensor-maps/', d, 1)

    def test_reprise_des_fichiers(self):
        from collector.etat import restaurer_depuis_site
        manifeste = {'sources': {'emsc': {'fichiers': ['emsc.json']}}}
        http = FauxHttp({'manifest.json': FausseReponse(json.dumps(manifeste)), 'emsc.json': FausseReponse(b'{"ok":1}')})
        with tempfile.TemporaryDirectory() as d:
            restaurer_depuis_site(http, 'https://moi.github.io/sensor-maps/', d, 1)
            self.assertEqual(json.loads((Path(d) / 'emsc.json').read_text()), {'ok': 1})


class TestOutils(unittest.TestCase):
    def test_dates(self):
        self.assertEqual(outils.iso(outils.lire_iso('2026-10-25T11:05:59.0Z')), '2026-10-25T11:05:59Z')
        self.assertEqual(outils.iso(outils.lire_iso('2026-10-25T13:00:00+02:00')), '2026-10-25T11:00:00Z')
        self.assertEqual(outils.iso(outils.lire_iso('2026-10-25 11:19:54')), '2026-10-25T11:19:54Z')
        self.assertIsNone(outils.lire_iso('pas une date'))

    def test_nombres(self):
        self.assertEqual(outils.nombre('239,12'), 239.12)
        self.assertIsNone(outils.nombre(''))
        self.assertIsNone(outils.nombre('nan'))
        self.assertEqual(outils.mediane([5, 1, 3]), 3)
        self.assertEqual(outils.mediane([4, 1, 3, 2]), 2.5)

    def test_adresse_sans_parametres(self):
        self.assertEqual(outils.url_courte('https://x.org/a?apikey=secret'), 'https://x.org/a')


if __name__ == '__main__':
    unittest.main()
