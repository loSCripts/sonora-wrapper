"""Règle du projet : aucun tiret long (U+2014) ni tiret moyen (U+2013), nulle part."""
import unittest
from pathlib import Path

RACINE = Path(__file__).resolve().parents[2]
EXTENSIONS = {'.py', '.html', '.md', '.yml', '.yaml', '.txt', '.json', '.csv', '.js', '.css', '.xml'}
INTERDITS = {chr(0x2014): 'tiret long', chr(0x2013): 'tiret moyen'}


class TestTirets(unittest.TestCase):
    def test_aucun_tiret_long_ou_moyen(self):
        fautes = []
        for chemin in RACINE.rglob('*'):
            if not chemin.is_file() or chemin.suffix not in EXTENSIONS or '.git' in chemin.parts:
                continue
            texte = chemin.read_text(encoding='utf-8', errors='replace')
            for n, ligne in enumerate(texte.splitlines(), 1):
                for car, nom in INTERDITS.items():
                    if car in ligne:
                        fautes.append('%s:%d (%s)' % (chemin.relative_to(RACINE), n, nom))
        self.assertEqual(fautes, [], 'Tirets interdits trouvés :\n' + '\n'.join(fautes))


if __name__ == '__main__':
    unittest.main()
