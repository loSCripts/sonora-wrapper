# Sensor Maps, étape 1

Une carte qui rassemble des mesures publiques **récentes** : séismes, pression et température de l'air, qualité de l'air, débit mobile, et bruit.

Un petit programme tourne tout seul sur GitHub toutes les 10 minutes. Il va chercher les données chez chaque service, les range dans de petits fichiers, puis publie la carte sur GitHub Pages. Pas d'application, pas de serveur à payer, aucune donnée inventée.

La carte : https://loscripts.github.io/sensor-maps/

## Ce que contient ce dépôt

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

## Mise en route

Le code est déjà dans ce dépôt. Tout se fait depuis le navigateur du téléphone, sur github.com.

### 1. Activer GitHub Pages

Ouvre https://github.com/loSCripts/sensor-maps/settings/pages

Dans « Build and deployment », menu « Source », choisis **GitHub Actions**. C'est tout : il n'y a rien à enregistrer.

### 2. Lancer le premier passage

Ouvre https://github.com/loSCripts/sensor-maps/actions/workflows/collecte.yml

Appuie sur **Run workflow**, coche « Interroger toutes les sources tout de suite », puis appuie sur le bouton vert **Run workflow**. Le passage dure en général 1 à 3 minutes (un peu plus la première fois, à cause des gros fichiers de l'ARCEP).

### 3. Ouvrir la carte

https://loscripts.github.io/sensor-maps/

Ensuite, tout est automatique : la collecte repasse toute seule toutes les 10 minutes.

## Ajouter la clé Météo-France (gratuite, recommandée)

Sans cette clé, la pression et la température viennent seulement des aéroports et des capteurs citoyens. Avec elle, on ajoute plus de 2 000 stations officielles en France.

Les noms ci-dessous sont ceux affichés par le portail (relevés dans son code le 25 octobre 2026).

1. Ouvre https://portail-api.meteofrance.fr, crée un compte gratuit, puis appuie sur **Se connecter**.
2. Ouvre directement la page de l'API : https://portail-api.meteofrance.fr/web/fr/api/DonneesPubliquesPaquetObservation
   Son nom affiché est **Package Observations**. Attention : ce n'est pas « Données d'observation », qui est une autre API.
3. Appuie sur **Souscrire à l'API gratuitement**.
4. Sur la page « Félicitations, vous êtes désormais abonné.e à l'API », appuie sur **Générer token**.
   Si tu as quitté cette page : ouvre le menu **Mes API**, puis appuie sur **Générer Token** sur la ligne de Package Observations.
5. Dans **Type de token**, choisis **API Key**. Ne prends pas « OAuth2 » : ce jeton ne dure qu'une heure.
6. Dans le champ **Durée** (il est écrit « Durée de validité désirée (en seconde) »), tape `94672800`. C'est le maximum accepté par le portail, soit environ 3 ans.
7. Appuie sur **Générer token**. Si une case « c'est bien noté » apparaît, coche-la. La clé s'affiche sous **Votre token** : copie-la tout de suite, le portail ne la montrera plus jamais.
8. Ouvre https://github.com/loSCripts/sensor-maps/settings/secrets/actions/new
9. Dans « Name », écris `METEOFRANCE_API_KEY`. Dans « Secret », colle la clé. Appuie sur **Add secret**. La clé reste secrète : elle n'apparaît jamais sur le site.
10. Relance un passage (étape 2 de la mise en route).

Dans environ 3 ans, la clé expirera : la page « Sources et licences » affichera alors « clé refusée ». Il suffira d'en générer une nouvelle (étapes 4 à 7) et de remplacer le secret.

## Ajouter la clé OpenAQ (facultative)

OpenAQ ajoute des stations du monde entier pour la qualité de l'air. Crée un compte sur https://explore.openaq.org et copie ta clé d'API depuis ton profil. Puis, comme pour Météo-France, ajoute un secret nommé `OPENAQ_API_KEY` sur https://github.com/loSCripts/sensor-maps/settings/secrets/actions/new

## Indiquer un contact (recommandé)

Sensor.Community demande qu'on indique un moyen de contact dans les requêtes. Par défaut, le programme donne l'adresse de ce dépôt. Pour donner ton e-mail à la place, ouvre https://github.com/loSCripts/sensor-maps/settings/variables/actions/new, écris `CONTACT` dans « Name » et ton e-mail dans « Value ».

## Ce que fait chaque passage

1. Il relit les fichiers déjà publiés sur le site.
2. Il interroge seulement les sources dont c'est l'heure (séismes toutes les 10 min, météo toutes les 30 min, air toutes les heures, débit chaque semaine).
3. Si une source est en panne, ses dernières données sont gardées, et la carte indique « périmé depuis… ». Les autres sources ne sont pas touchées.
4. Il publie le site. Rien n'est enregistré dans l'historique du dépôt, qui ne grossit donc pas.

Si le site lui-même ne répond pas au début du passage, rien n'est publié cette fois-ci : le site garde sa version précédente.

## Si quelque chose ne va pas

- **La carte dit « Mesures indisponibles »** : le premier passage n'a pas encore eu lieu. Refais l'étape 2 de la mise en route.
- **Une source est « En panne »** sur la page « Sources et licences » : le message d'erreur y est affiché. C'est souvent passager ; la carte garde les dernières données.
- **GitHub t'envoie un e-mail « Run failed »** : ouvre https://github.com/loSCripts/sensor-maps/actions, touche le passage en rouge, puis l'étape en rouge pour lire l'erreur.
- **GitHub a coupé la collecte** (il le fait après 60 jours sans activité sur un dépôt public) : la tâche « Maintien » l'évite en écrivant une date dans `.github/derniere-activite.txt` toutes les 6 semaines environ. Si ça arrive quand même, ouvre la page de l'étape 2 de la mise en route et appuie sur **Enable workflow**.
- **Modifier la carte** : sur github.com, ouvre `web/carte.html`, touche le crayon, modifie (le bloc « CONFIGURATION » en haut du script est fait pour ça), puis **Commit changes**. Le site se republie tout seul.

## Choix faits par défaut

- Seulement des sources dont la licence permet l'usage commercial. **Exclues** : Ookla (aussi dans les fichiers de l'ARCEP) et Open-Meteo gratuit (non commerciaux), M-Lab (statistiques publiques arrêtées en 2024, accès par compte Google Cloud), INTERMAGNET (non commercial).
- Ajoutées en plus du plan, car gratuites, sans clé et commerciales : les **aéroports (METAR)** pour avoir la pression dès le premier jour, les **stations officielles du LCSQA** pour l'air en France, et les **capteurs de bruit** de Sensor.Community.
- Sur la carte, chaque zone colorée montre la **médiane** des mesures qu'elle contient. Là où rien n'est mesuré, rien n'est dessiné.
- Les positions des capteurs citoyens sont arrondies à environ 100 mètres.
- Le champ magnétique et la lumière restent grisés : « Pas encore de données ouvertes récentes ».
- La page de test des capteurs de la maquette n'est pas publiée : elle ne montrait que des valeurs simulées. Elle reviendra avec l'application (étape 2).
- Le bouton « Soutenir » propose de partager la carte et de voir le code. Aucun réseau publicitaire, aucun traceur. Pour ajouter tes liens de dons, remplis `LIENS_SOUTIEN` en haut du script de `web/carte.html`.

## Travailler depuis Termux (facultatif)

Tape les commandes **une par une**, en attendant la fin de chacune.

```
pkg install -y git gh python
```

```
gh auth login -s workflow
```

Réponds : `GitHub.com`, `HTTPS`, `Y`, puis `Login with a web browser`, et colle le code à 8 caractères dans la page qui s'ouvre. Le petit ajout `-s workflow` donne le droit d'envoyer des modifications des tâches automatiques.

```
gh repo clone loSCripts/sensor-maps
```

```
cd sensor-maps
```

Les tests rejouent des réponses enregistrées : ils n'utilisent pas le réseau.

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

Pas encore testé : Météo-France et OpenAQ (il faut une clé). Le journal du premier passage sur GitHub Actions dira si tout va bien.
