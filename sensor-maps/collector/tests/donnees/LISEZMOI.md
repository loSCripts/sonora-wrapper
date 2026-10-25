# Données des tests

Ces fichiers servent seulement aux tests automatiques. Ils ne sont jamais publiés sur la carte.

Extraits de **vraies réponses**, enregistrées le 25 octobre 2026 vers 11 h 20 UTC (raccourcies) :

- `emsc_extrait.txt` : EMSC, service FDSN, format texte
- `usgs_extrait.json` : USGS, flux « 2.5_week »
- `metar_extrait.csv.gz` et `stations_awc_extrait.json.gz` : NOAA Aviation Weather Center
- `sensor_community_extrait.json` : Sensor.Community, fichier « data.1h.json »
- `lcsqa_extrait.xml` : LCSQA, fichier « FR_E2 » du jour (deux stations)
- `lcsqa_stations_lignes.json` : trois lignes de la feuille « AirQualityStations » du fichier « Dataset D »
- `arcep_extrait.csv` : ARCEP, campagne 2025 en métropole (quelques lignes)

Fichiers **construits à la main** d'après la documentation, faute de clé pour faire un vrai appel
(le nom contient « construit ») :

- `meteofrance_exemple_construit.json` et `meteofrance_stations_exemple_construit.csv`
- `openaq_*_construit.json`

Quand les clés seront ajoutées, le premier passage sur GitHub dira si le vrai format correspond.
