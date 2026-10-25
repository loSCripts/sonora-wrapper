"""Une source par fichier. Chacune est indépendante : si l'une échoue, les autres continuent."""
from . import arcep, emsc, lcsqa, metar, meteofrance, openaq, sensor_community, usgs

TOUTES = [emsc, usgs, meteofrance, metar, sensor_community, lcsqa, openaq, arcep]
