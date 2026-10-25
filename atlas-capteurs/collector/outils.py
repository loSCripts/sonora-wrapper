"""Outils communs : téléchargement poli, dates en UTC, nombres, fichiers JSON."""
import datetime as dt
import json
import os
import re
import tempfile
import time
from pathlib import Path

import requests

UTC = dt.timezone.utc


class ErreurSource(Exception):
    """Problème attendu avec une source : réponse vide, format changé, clé refusée, panne."""


# ---------- Dates ----------

def maintenant():
    return dt.datetime.now(UTC).replace(microsecond=0)


def iso(moment):
    if moment is None:
        return None
    return moment.astimezone(UTC).strftime('%Y-%m-%dT%H:%M:%SZ')


_ISO = re.compile(r'^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?\s*(Z|[+-]\d{2}:?\d{2})?$')


def lire_iso(texte):
    """Lit une date ISO 8601 (avec ou sans fuseau, avec ou sans fractions). Sans fuseau : UTC."""
    if not texte:
        return None
    m = _ISO.match(str(texte).strip())
    if not m:
        return None
    a, mo, j, h, mi, s, _frac, fuseau = m.groups()
    moment = dt.datetime(int(a), int(mo), int(j), int(h), int(mi), int(s or 0), tzinfo=UTC)
    if fuseau and fuseau != 'Z':
        signe = 1 if fuseau[0] == '+' else -1
        chiffres = fuseau[1:].replace(':', '')
        decalage = dt.timedelta(hours=int(chiffres[:2]), minutes=int(chiffres[2:]))
        moment = moment - signe * decalage
    return moment


def epoch(moment):
    return int(moment.timestamp())


# ---------- Nombres ----------

def nombre(texte):
    """'12,5' ou '12.5' ou 12.5 donne 12.5 ; vide ou illisible donne None."""
    if texte is None or isinstance(texte, bool):
        return None
    if isinstance(texte, (int, float)):
        v = float(texte)
    else:
        texte = str(texte).strip().replace(',', '.')
        if not texte:
            return None
        try:
            v = float(texte)
        except ValueError:
            return None
    if v != v or v in (float('inf'), float('-inf')):
        return None
    return v


def arrondi(v, decimales):
    return None if v is None else round(v, decimales)


def mediane(valeurs):
    v = sorted(valeurs)
    n = len(v)
    if not n:
        return None
    return v[n // 2] if n % 2 else (v[n // 2 - 1] + v[n // 2]) / 2


_TIRETS = {0x2013: '-', 0x2014: '-', 0x2212: '-'}


def texte_propre(texte):
    """Nom lisible : espaces en trop retirés, tirets typographiques remplacés par un tiret simple."""
    return ' '.join(str(texte or '').translate(_TIRETS).split())


def coordonnees_valides(lat, lon):
    return lat is not None and lon is not None and -90 <= lat <= 90 and -180 <= lon <= 180 and not (lat == 0 and lon == 0)


# ---------- Fichiers ----------

def ecrire_json(chemin, objet, lisible=False):
    """Écrit d'abord dans un fichier temporaire, puis remplace : jamais de fichier à moitié écrit."""
    chemin = Path(chemin)
    chemin.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(dir=chemin.parent, prefix='.tmp-', suffix='.json')
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        if lisible:
            json.dump(objet, f, ensure_ascii=False, indent=1)
        else:
            json.dump(objet, f, ensure_ascii=False, separators=(',', ':'))
    os.chmod(temp, 0o644)       # lisible par tous : c'est un fichier publié
    os.replace(temp, chemin)


def lire_json(chemin):
    try:
        with open(chemin, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


# ---------- Réseau ----------

def url_courte(url):
    """Adresse sans ses paramètres : rien de secret ne peut fuiter dans un message."""
    return str(url).split('?', 1)[0]


class Http:
    """Client HTTP poli : en-tête avec un contact, délai d'attente, quelques essais si la panne est passagère."""

    REESSAYER = (429, 500, 502, 503, 504)

    def __init__(self, agent, essais=3, pause=3.0):
        self.session = requests.Session()
        self.session.headers.update({'User-Agent': agent, 'Accept-Encoding': 'gzip, deflate'})
        self.essais = essais
        self.pause = pause

    def _requete(self, methode, url, codes_ok, essais=None, **options):
        derniere = None
        essais = essais or self.essais
        for essai in range(1, essais + 1):
            try:
                r = self.session.request(methode, url, **options)
            except requests.RequestException as e:
                derniere = 'réseau (' + e.__class__.__name__ + ')'
            else:
                if r.status_code in codes_ok:
                    return r
                derniere = 'HTTP ' + str(r.status_code)
                r.close()
                if r.status_code not in self.REESSAYER:
                    break
            if essai < essais:
                time.sleep(self.pause * essai)
        raise ErreurSource(derniere + ' sur ' + url_courte(url))

    def get(self, url, params=None, headers=None, delai=60, flux=False, codes_ok=(200,), essais=None):
        return self._requete('GET', url, codes_ok, essais=essais, params=params, headers=headers, timeout=delai, stream=flux)

    def head(self, url, headers=None, delai=30, codes_ok=(200,)):
        return self._requete('HEAD', url, codes_ok, headers=headers, timeout=delai, allow_redirects=True)

    def post(self, url, params=None, headers=None, data=None, delai=30, codes_ok=(200,)):
        return self._requete('POST', url, codes_ok, params=params, headers=headers, data=data, timeout=delai)

    def telecharger(self, url, chemin, delai=120):
        """Télécharge un gros fichier morceau par morceau, sans tout garder en mémoire."""
        r = self.get(url, delai=delai, flux=True)
        with r, open(chemin, 'wb') as f:
            for morceau in r.iter_content(chunk_size=1 << 20):
                f.write(morceau)
        return r.headers
