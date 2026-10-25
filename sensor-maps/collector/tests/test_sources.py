"""Chaque source lit correctement une réponse enregistrée (vraie, ou construite quand on n'a pas de clé)."""
import gzip
import json
import tempfile
import unittest

from collector import outils
from collector.outils import ErreurSource
from collector.sources import arcep, emsc, lcsqa, metar, meteofrance, openaq, sensor_community, usgs
from collector.tests.aide import FausseReponse, FauxHttp, contexte, fichier

MOMENT = '2026-10-25T11:30:00Z'


class TestEmsc(unittest.TestCase):
    def test_lecture_du_format_texte(self):
        evs = list(emsc.lire_texte(fichier('emsc_extrait.txt').decode('utf-8')))
        self.assertEqual(len(evs), 25)
        premier = evs[0]
        self.assertEqual(premier[0], '20261025_0000148')
        self.assertEqual(premier[5], 3.6)
        self.assertEqual(premier[7], 'PANAMA')
        self.assertEqual(premier[1], outils.epoch(outils.lire_iso('2026-10-25T11:05:59Z')))

    def test_reponse_trop_courte_refusee(self):
        http = FauxHttp({'seismicportal.eu': FausseReponse('#EventID|Time\n')})
        with tempfile.TemporaryDirectory() as d, self.assertRaises(ErreurSource):
            emsc.collecter(contexte(http, d, MOMENT))

    def test_collecte_complete(self):
        http = FauxHttp({'seismicportal.eu': FausseReponse(fichier('emsc_extrait.txt'))})
        with tempfile.TemporaryDirectory() as d:
            res = emsc.collecter(contexte(http, d, MOMENT))
        self.assertEqual(res.nb, 25)      # les deux zones renvoient les mêmes séismes : pas de doublon
        evs = res.fichiers['emsc.json']['evenements']
        self.assertEqual(evs, sorted(evs, key=lambda e: -e[1]))


class TestUsgs(unittest.TestCase):
    def test_lecture_geojson(self):
        evs = list(usgs.lire_geojson(json.loads(fichier('usgs_extrait.json'))))
        self.assertEqual(len(evs), 15)
        self.assertEqual(evs[0][0], 'pr71537633')
        self.assertEqual(evs[0][5], 3.4)
        self.assertTrue(evs[0][8].startswith('https://earthquake.usgs.gov/'))

    def test_format_inattendu(self):
        with self.assertRaises(ErreurSource):
            list(usgs.lire_geojson({'type': 'autre'}))


class TestMetar(unittest.TestCase):
    def test_pression_groupe_q_exact(self):
        self.assertEqual(metar.pression_metar('METAR LFPG 251100Z 22008KT 9999 SCT030 14/09 Q1007 NOSIG', '29.73'), 1007.0)

    def test_pression_groupe_a_converti(self):
        self.assertEqual(metar.pression_metar('METAR KP08 251120Z AUTO 00000KT 10SM 16/09 A3002 RMK AO2', '30.02'), 1016.6)

    def test_remarques_ignorees(self):
        self.assertEqual(metar.pression_metar('METAR XXXX 251120Z 16/09 RMK A0123', '29.92'), round(29.92 * metar.INHG_EN_HPA, 1))

    def test_lecture_du_cache(self):
        texte = gzip.decompress(fichier('metar_extrait.csv.gz')).decode('utf-8')
        stations = metar.lire_csv(texte, outils.lire_iso(MOMENT))
        self.assertGreater(len(stations), 30)
        par_code = {s[0]: s for s in stations}
        self.assertIn('LFPG', par_code)
        self.assertTrue(870 <= par_code['LFPG'][4] <= 1085)

    def test_releves_trop_vieux_ecartes(self):
        texte = gzip.decompress(fichier('metar_extrait.csv.gz')).decode('utf-8')
        self.assertEqual(metar.lire_csv(texte, outils.lire_iso('2026-10-26T12:00:00Z')), [])

    def test_noms_des_aeroports(self):
        http = FauxHttp({'metars.cache': FausseReponse(fichier('metar_extrait.csv.gz')),
                         'stations.cache': FausseReponse(fichier('stations_awc_extrait.json.gz'))})
        metar.MINIMUM, ancien = 10, metar.MINIMUM
        try:
            with tempfile.TemporaryDirectory() as d:
                res = metar.collecter(contexte(http, d, MOMENT))
        finally:
            metar.MINIMUM = ancien
        self.assertIn('Gaulle', res.fichiers['metar.json']['noms']['LFPG'])


class TestSensorCommunity(unittest.TestCase):
    def setUp(self):
        self.liste = json.loads(fichier('sensor_community_extrait.json'))

    def test_interieur_et_valeurs_aberrantes_ecartes(self):
        pm25, temperature, pression, bruit, _ = sensor_community.extraire(self.liste)
        self.assertTrue(all(0 <= p[2] <= 500 for p in pm25))
        self.assertTrue(all(870 <= p[2] <= 1085 for p in pression))
        interieurs = {(round(float(x['location']['latitude']), 3), round(float(x['location']['longitude']), 3))
                      for x in self.liste if x['location']['indoor'] == 1}
        dehors = {(round(float(x['location']['latitude']), 3), round(float(x['location']['longitude']), 3))
                  for x in self.liste if x['location']['indoor'] == 0}
        for p in pm25 + temperature + pression:
            self.assertFalse((p[0], p[1]) in interieurs - dehors)

    def test_positions_arrondies_a_100_m(self):
        pm25, temperature, pression, bruit, _ = sensor_community.extraire(self.liste)
        for p in pm25 + temperature + pression + bruit:
            self.assertEqual(p[0], round(p[0], 3))
            self.assertEqual(p[1], round(p[1], 3))

    def test_bruit_lu(self):
        _, _, _, bruit, _ = sensor_community.extraire(self.liste)
        self.assertGreater(len(bruit), 0)
        self.assertTrue(all(20 <= b[2] <= 130 for b in bruit))

    def test_pression_en_hpa(self):
        _, _, pression, _, _ = sensor_community.extraire(self.liste)
        self.assertGreater(len(pression), 5)


class TestLcsqa(unittest.TestCase):
    def test_positions_des_stations(self):
        ref = lcsqa.lignes_stations(json.loads(fichier('lcsqa_stations_lignes.json')))
        self.assertEqual(ref['FR01011'][0], 'Metz-Centre')
        self.assertAlmostEqual(ref['FR01011'][2], 49.12, places=1)

    def test_xml_derniere_valeur_valide(self):
        mesures = lcsqa.lire_xml(fichier('lcsqa_extrait.xml'))
        self.assertIn('FR01011', mesures)
        self.assertEqual(set(mesures['FR01011']) - {'pm25', 'pm10', 'no2', 'o3'}, set())
        v, fin = mesures['FR01011']['no2']
        self.assertEqual(fin, outils.epoch(outils.lire_iso('2026-10-25T10:00:00+00:00')))
        self.assertEqual(v, 1.3)

    def test_collecte_complete(self):
        lignes = json.loads(fichier('lcsqa_stations_lignes.json'))
        http = FauxHttp({'FR_E2_2026-10-25.xml': FausseReponse(fichier('lcsqa_extrait.xml')),
                         'FR_E2_2026-10-24.xml': FausseReponse(b'', 404)})
        with tempfile.TemporaryDirectory() as d:
            outils.ecrire_json(d + '/ref_lcsqa_stations.json',
                               {'maj': outils.epoch(outils.lire_iso(MOMENT)), 'stations': lcsqa.lignes_stations(lignes)})
            lcsqa.MINIMUM, ancien = 1, lcsqa.MINIMUM
            try:
                res = lcsqa.collecter(contexte(http, d, MOMENT))
            finally:
                lcsqa.MINIMUM = ancien
        stations = res.fichiers['lcsqa.json']['stations']
        self.assertEqual([s[0] for s in stations], ['FR01011', 'FR04002'])
        self.assertIn('pm25', stations[0][5])


class TestArcep(unittest.TestCase):
    def test_medianes_par_lieu_et_operateur(self):
        import csv
        import io
        texte = fichier('arcep_extrait.csv').decode('utf-8-sig')
        operateurs, lieux, nb, periode = arcep.agreger(csv.DictReader(io.StringIO(texte), delimiter=';'))
        self.assertEqual(sorted(operateurs), ['Bouygues', 'Free', 'Orange', 'SFR'])
        self.assertEqual(nb, 20)
        self.assertEqual(len(lieux), 3)
        for lat, lon, ops in lieux:
            for op, med, n in ops:
                self.assertTrue(0 <= med < 2000 and n >= 1)
        self.assertIn('2025', periode)

    def test_periode_en_toutes_lettres(self):
        self.assertEqual(arcep.periode_texte(['23/06/2025', '02/09/2025', '15/05/2025']), 'mai à septembre 2025')
        self.assertEqual(arcep.periode_texte(['01/12/2025', '10/01/2026']), 'décembre 2025 à janvier 2026')

    def test_fichier_inchange_pas_retelecharge(self):
        entetes = {'Last-Modified': 'Tue, 26 May 2026 18:05:46 GMT', 'Content-Length': '93293735'}
        http = FauxHttp({'2026/Metropole': FausseReponse(b'', 404), '2025/Metropole': FausseReponse(b'', 200, entetes),
                         'Outremer': FausseReponse(b'', 404)})
        with tempfile.TemporaryDirectory() as d:
            bloc = {'territoire': 'Métropole', 'annee': 2025, 'periode': 'mai à septembre 2025',
                    'url': arcep.URL.format(annee=2025, terr='Metropole'), 'modifie': entetes['Last-Modified'],
                    'taille': entetes['Content-Length'], 'mesures': 33384, 'operateurs': ['Orange'], 'lieux': []}
            outils.ecrire_json(d + '/arcep.json', {'blocs': [bloc]})
            res = arcep.collecter(contexte(http, d, MOMENT))
        self.assertEqual(res.garder, ['arcep.json'])
        self.assertEqual(res.fichiers, {})
        self.assertNotIn('telecharger', [methode for methode, _url in http.appels])


class TestMeteoFrance(unittest.TestCase):
    """Réponses construites d'après la documentation : à confirmer au premier vrai passage."""

    def test_sans_cle_la_source_attend(self):
        self.assertIn('METEOFRANCE_API_KEY', meteofrance.cles_manquantes(contexte(None, '.', MOMENT)))

    def test_conversion_des_unites(self):
        recs = meteofrance.enregistrements(json.loads(fichier('meteofrance_exemple_construit.json')))
        ref = meteofrance.lire_liste_stations(fichier('meteofrance_stations_exemple_construit.csv').decode('utf-8'))
        liste = meteofrance.convertir(recs, ref, outils.lire_iso(MOMENT))
        par_id = {s[0]: s for s in liste}
        paris = par_id['75114001']
        self.assertEqual(paris[1], 'PARIS-MONTSOURIS')
        self.assertEqual(paris[6], 1013.3)     # pascals vers hectopascals
        self.assertEqual(paris[8], 14.3)       # kelvins vers degrés Celsius
        self.assertIsNone(par_id['84031001'][6])
        self.assertNotIn('99999999', par_id)   # sans position, la station est ignorée

    def test_geojson_accepte(self):
        gj = {'type': 'FeatureCollection', 'features': [
            {'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [2.3375, 48.821667]},
             'properties': {'geo_id_insee': '75114001', 'validity_time': '2026-10-25T11:30:00Z', 't': 287.45, 'pmer': 101330}}]}
        recs = meteofrance.enregistrements(gj)
        liste = meteofrance.convertir(recs, {}, outils.lire_iso(MOMENT))
        self.assertEqual(liste[0][6], 1013.3)

    def test_cle_refusee_message_clair(self):
        http = FauxHttp({'liste-stations': FausseReponse(b'', 401), 'infrahoraire': FausseReponse(b'', 401)})
        with tempfile.TemporaryDirectory() as d, self.assertRaises(ErreurSource) as e:
            meteofrance.collecter(contexte(http, d, MOMENT, env={'METEOFRANCE_API_KEY': 'x'}))
        self.assertIn('clé refusée', str(e.exception))


class TestOpenAQ(unittest.TestCase):
    """Réponses construites d'après la documentation v3 : à confirmer au premier vrai passage."""

    def test_seules_les_licences_commerciales_passent(self):
        autorisees = openaq.licences_commerciales(json.loads(fichier('openaq_licences_construit.json'))['results'])
        lieux = openaq.lieux_autorises(json.loads(fichier('openaq_lieux_construit.json'))['results'], autorisees)
        self.assertEqual(list(lieux), ['2178'])     # la licence non commerciale et l'absence de licence sont écartées

    def test_collecte_complete(self):
        openaq.PAUSE_S, pause = 0, openaq.PAUSE_S
        openaq.MINIMUM, minimum = 1, openaq.MINIMUM
        http = FauxHttp({'/parameters/2/latest': FausseReponse(fichier('openaq_derniers_construit.json')),
                         '/parameters/2': FausseReponse(fichier('openaq_parametre_construit.json')),
                         '/licenses': FausseReponse(fichier('openaq_licences_construit.json')),
                         '/locations': FausseReponse(fichier('openaq_lieux_construit.json'))})
        try:
            with tempfile.TemporaryDirectory() as d:
                res = openaq.collecter(contexte(http, d, MOMENT, env={'OPENAQ_API_KEY': 'x'}))
        finally:
            openaq.PAUSE_S, openaq.MINIMUM = pause, minimum
        stations = res.fichiers['openaq.json']['stations']
        self.assertEqual(len(stations), 1)            # la valeur de 5 jours est trop vieille
        self.assertEqual(stations[0][6], 7.2)


if __name__ == '__main__':
    unittest.main()
