# Atlas des capteurs, étape 1

Une carte qui rassemble des mesures publiques **récentes** : séismes, pression et température de l'air, qualité de l'air, débit mobile, et bruit.

Un petit programme tourne tout seul sur GitHub toutes les 10 minutes. Il va chercher les données chez chaque service, les range dans de petits fichiers, puis publie la carte sur GitHub Pages. Pas d'application, pas de serveur à payer, aucune donnée inventée.

## Ce que contient ce dossier

- `web/` : la carte (`carte.html`), la page « Sources et licences » (`sources.html`), et `index.html` qui ouvre la carte.
- `collector/` : le programme de collecte en Python. Un fichier par source dans `collector/sources/`, et des tests dans `collector/tests/`.
- `.github/workflows/` : les trois tâches automatiques de GitHub.
  - `collecte.yml` : la collecte et la publication, toutes les 10 minutes.
  - `maintien.yml` : chaque lundi, évite que GitHub coupe la collecte (voir plus bas).
  - `verifier.yml` : relance les tests à chaque modification du code.

## Les sources

| Couche | Source | Rythme | Clé | Licence |
|---|---|---|---|---|
| Séismes | EMSC (Centre sismologique euro-méditerranéen) | 10 min | non | CC BY 4.0 |
| Séismes | USGS (États-Unis) | 10 min | non | domaine public |
| Pression, température | Météo-France, « Package Observations » | 30 min | **oui, gratuite** | Licence Ouverte 2.0 |
| Pression, température | Aéroports du monde entier (METAR, NOAA) | 30 min | non | domaine public |
| Air, pression, température, bruit | Sensor.Community (capteurs citoyens) | 1 h | non | DbCL 1.0 |
| Air | LCSQA (stations officielles en France) | 1 h | non | Licence Ouverte 2.0 |
| Air | OpenAQ (stations du monde entier) | 1 h | oui, gratuite, facultative | variable : seules les stations à licence commerciale sont gardées |
| Débit mobile | ARCEP, Mon Réseau Mobile | 1 semaine | non | Licence Ouverte 2.0 |

Toutes ces licences permettent un usage commercial : une page de liens de soutien ou de publicité reste donc possible plus tard. Le détail et les sources écartées sont sur la page « Sources et licences » du site.

## Mise en route depuis ton téléphone

Il te faut :

- l'application **Termux** (à installer depuis F-Droid) ;
- ton compte **GitHub** ;
- le fichier **atlas-capteurs.zip** dans le dossier « Téléchargements » du téléphone.

Tape les commandes **une par une**. Attends que chacune soit finie avant de passer à la suivante.

### 1. Installer les outils

```
pkg install -y git gh unzip
```

### 2. Autoriser Termux à lire tes téléchargements

```
termux-setup-storage
```

Android te demande une autorisation : accepte.

### 3. Te connecter à GitHub

```
gh auth login -s workflow
```

Réponds dans l'ordre : `GitHub.com`, puis `HTTPS`, puis `Y`, puis `Login with a web browser`. Termux affiche un code de 8 caractères : copie-le, ouvre le lien proposé dans ton navigateur, colle le code et valide.

Le petit ajout `-s workflow` donne le droit d'envoyer les fichiers de tâches automatiques : sans lui, GitHub refuse l'envoi de l'étape 7. Si tu étais déjà connecté avec `gh`, tape plutôt `gh auth refresh -s workflow`.

### 4. Décompresser le ZIP

```
unzip ~/storage/downloads/atlas-capteurs.zip -d ~
```

Si ton téléphone a renommé le fichier (par exemple `atlas-capteurs (1).zip`), adapte le nom dans la commande.

### 5. Entrer dans le dossier

```
cd ~/atlas-capteurs
```

### 6. Préparer le premier envoi

```
git init -b main
```

```
git add .
```

```
git commit -m "Etape 1 : carte des sources publiques"
```

Si git te demande qui tu es, tape ces deux commandes (avec tes informations), puis refais le `git commit` :

```
git config --global user.name "Ton nom"
```

```
git config --global user.email "ton.adresse@exemple.fr"
```

### 7. Créer le dépôt sur GitHub et y envoyer le code

```
gh repo create atlas-capteurs --public --source=. --remote=origin --push
```

Le dépôt doit être **public** : c'est gratuit et sans limite de minutes pour GitHub Actions et GitHub Pages.

Juste après l'envoi, GitHub lance une première collecte qui s'arrête tout de suite avec un avertissement jaune « GitHub Pages n'est pas encore activé ». C'est normal : c'est l'étape suivante.

### 8. Activer GitHub Pages

```
gh api -X POST "repos/{owner}/{repo}/pages" -f build_type=workflow
```

Tu peux aussi le faire sur github.com : dans ton dépôt, `Settings`, puis `Pages`, puis sous « Build and deployment », choisis la source `GitHub Actions`.

### 9. Lancer le premier vrai passage

```
gh workflow run collecte.yml -f forcer=true
```

### 10. Suivre le passage

```
gh run watch
```

Choisis le passage « Collecte et publication » dans la liste. Il dure en général 1 à 3 minutes (un peu plus la première fois, à cause des gros fichiers de l'ARCEP).

### 11. Ouvrir la carte

```
gh api "repos/{owner}/{repo}/pages" --jq .html_url
```

Cette commande affiche l'adresse de ta carte, de la forme `https://ton-nom.github.io/atlas-capteurs/`. Ouvre-la dans ton navigateur.

Ensuite, tout est automatique : la collecte repasse toute seule toutes les 10 minutes.

## Ajouter la clé Météo-France (gratuite, recommandée)

Sans cette clé, la pression et la température viennent seulement des aéroports et des capteurs citoyens. Avec elle, on ajoute plus de 2 000 stations officielles en France.

1. Ouvre https://portail-api.meteofrance.fr et crée un compte gratuit.
2. Dans le catalogue, choisis l'API **« Package Observations »** et abonne-toi.
3. Dans **« Mes API »**, génère une **clé d'API** (« API Key »). Si on te demande une durée, prends la plus longue proposée. Copie la clé.
4. Dans Termux, dans le dossier `~/atlas-capteurs`, tape :

```
gh secret set METEOFRANCE_API_KEY
```

Colle la clé quand Termux la demande, puis valide. La clé reste secrète : elle n'apparaît jamais sur le site.

5. Relance un passage :

```
gh workflow run collecte.yml -f forcer=true
```

Non vérifié : les noms exacts des boutons du portail et la durée maximale d'une clé. Si la clé expire, la page « Sources et licences » affiche « clé refusée » : il suffit d'en générer une nouvelle et de refaire l'étape 4.

Autre possibilité : au lieu de la clé, mets « l'identifiant d'application » (la longue valeur après `Authorization: Basic` dans l'exemple `curl` du portail) dans un secret nommé `METEOFRANCE_APPLICATION_ID`. Le programme demande alors lui-même un jeton à chaque passage, et rien n'expire.

## Ajouter la clé OpenAQ (facultative)

OpenAQ ajoute des stations du monde entier pour la qualité de l'air. Crée un compte sur https://explore.openaq.org, copie ta clé d'API dans ton profil, puis :

```
gh secret set OPENAQ_API_KEY
```

## Indiquer un contact (recommandé)

Sensor.Community demande qu'on indique un moyen de contact dans les requêtes. Par défaut, le programme donne l'adresse de ton dépôt. Pour donner ton e-mail à la place :

```
gh variable set CONTACT --body "ton.adresse@exemple.fr"
```

## Ce que fait chaque passage

1. Il relit les fichiers déjà publiés sur ton site.
2. Il interroge seulement les sources dont c'est l'heure (séismes toutes les 10 min, météo toutes les 30 min, air toutes les heures, débit chaque semaine).
3. Si une source est en panne, ses dernières données sont gardées, et la carte indique « périmé depuis… ». Les autres sources ne sont pas touchées.
4. Il publie le site. Rien n'est enregistré dans l'historique du dépôt, qui ne grossit donc pas.

Si le site lui-même ne répond pas au début du passage, rien n'est publié cette fois-ci : le site garde sa version précédente.

## Si quelque chose ne va pas

- **La carte dit « Mesures indisponibles »** : le premier passage n'a pas encore eu lieu. Refais l'étape 9.
- **Une source est « En panne »** sur la page « Sources et licences » : le message d'erreur y est affiché. C'est souvent passager ; la carte garde les dernières données.
- **GitHub t'envoie un e-mail « Run failed »** : pour voir l'erreur, tape :

```
gh run view --log-failed
```

- **GitHub a coupé la collecte** (il le fait après 60 jours sans activité sur un dépôt public) : la tâche « Maintien » l'évite en écrivant une date dans `.github/derniere-activite.txt` toutes les 6 semaines environ. Si ça arrive quand même :

```
gh workflow enable collecte.yml
```

- **Modifier la carte** : change `web/carte.html` (le bloc « CONFIGURATION » en haut du script est fait pour ça), puis `git add .`, `git commit -m "Ma modification"` et `git push`. Le site se republie tout seul.

## Choix faits par défaut

- Seulement des sources dont la licence permet l'usage commercial. **Exclues** : Ookla (aussi dans les fichiers de l'ARCEP) et Open-Meteo gratuit (non commerciaux), M-Lab (statistiques publiques arrêtées en 2024, accès par compte Google Cloud), INTERMAGNET (non commercial).
- Ajoutées en plus du plan, car gratuites, sans clé et commerciales : les **aéroports (METAR)** pour avoir la pression dès le premier jour, les **stations officielles du LCSQA** pour l'air en France, et les **capteurs de bruit** de Sensor.Community.
- Sur la carte, chaque zone colorée montre la **médiane** des mesures qu'elle contient. Là où rien n'est mesuré, rien n'est dessiné.
- Les positions des capteurs citoyens sont arrondies à environ 100 mètres.
- Le champ magnétique et la lumière restent grisés : « Pas encore de données ouvertes récentes ».
- La page de test des capteurs de la maquette n'est pas publiée : elle ne montrait que des valeurs simulées. Elle reviendra avec l'application (étape 2).
- Le bouton « Soutenir » propose de partager la carte et de voir le code. Aucun réseau publicitaire, aucun traceur. Pour ajouter tes liens de dons, remplis `LIENS_SOUTIEN` en haut du script de `web/carte.html`.

## Tester sur le téléphone (facultatif)

Les tests rejouent des réponses enregistrées : ils n'utilisent pas le réseau.

```
pkg install -y python
```

```
pip install -r collector/requirements.txt
```

```
python -m unittest discover -s collector/tests -t .
```

Pour faire un vrai passage sur le téléphone, puis voir la carte dans le navigateur à l'adresse `http://localhost:8000/` :

```
python -m collector --sortie _site --forcer
```

```
python -m http.server 8000 --directory _site
```

## État au 25 octobre 2026

Testé avec de vrais appels : EMSC, USGS, aéroports (METAR), Sensor.Community, LCSQA et ARCEP, puis la carte dans un navigateur de téléphone simulé.

Pas encore testé : Météo-France et OpenAQ (il faut une clé), et le premier passage sur GitHub Actions lui-même. Le journal du premier passage dira si tout va bien.
