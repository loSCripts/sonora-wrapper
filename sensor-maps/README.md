# Sensor Maps, étape 1

Une carte qui rassemble des mesures publiques **récentes** : séismes, pression et température de l'air, qualité de l'air, débit mobile, et bruit.

Un petit programme tourne tout seul sur GitHub toutes les 10 minutes. Il va chercher les données chez chaque service, les range dans de petits fichiers, puis publie la carte sur GitHub Pages. Pas d'application, pas de serveur à payer, aucune donnée inventée.

La carte : https://loscripts.github.io/sensor-maps/

## Ce que contient ce dépôt

- `web/` : la carte (`carte.html`), la page « Sources et licences » (`sources.html`), et `index.html` qui ouvre la carte.
- `collector/` : le programme de collecte en Python. Un fichier par source dans `collector/sources/`, et des tests dans `collector/tests/`.
- `appli-test/` : l'appli Android du test décisif, le début de l'étape 2 (voir « Appli de test » plus bas).
- `A-FAIRE.md` : ce qui reste à faire plus tard, pour ne rien oublier d'une conversation à l'autre.
- `.github/workflows/` : les trois tâches automatiques de GitHub.
  - `collecte.yml` : la collecte et la publication, toutes les 10 minutes. Elle évite aussi que GitHub coupe la collecte (voir plus bas).
  - `verifier.yml` : relance les tests à chaque modification du code.
  - `appli-test.yml` : fabrique l'appli de test, l'essaie sur un téléphone virtuel, puis la publie sur la page « Releases ».

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

Le code est déjà dans ce dépôt. Tout se fait depuis le navigateur du téléphone.

### 1. Activer GitHub Pages

Ouvre https://github.com/loSCripts/sensor-maps/settings/pages

Dans « Build and deployment », menu « Source », choisis **GitHub Actions**. C'est tout : il n'y a rien à enregistrer.

### 2. Lancer le premier passage

Ouvre https://github.com/loSCripts/sensor-maps/actions/workflows/collecte.yml

Appuie sur **Run workflow**, coche « Interroger toutes les sources tout de suite », puis appuie sur le bouton vert **Run workflow**. Le passage dure en général 1 à 3 minutes (un peu plus la première fois, à cause des gros fichiers de l'ARCEP).

### 3. Ouvrir la carte

https://loscripts.github.io/sensor-maps/

### 4. Faire repasser la collecte toutes les 10 minutes

La tâche de collecte contient un minuteur GitHub. Mais sur ce dépôt, le 25 octobre 2026, GitHub ne l'a jamais déclenché, sans donner de raison (la tâche est pourtant « active »). Le service gratuit cron-job.org le remplace : toutes les 10 minutes, il demande à GitHub de lancer la collecte, comme si tu appuyais sur **Run workflow**. Si un jour le minuteur de GitHub marche aussi, ce n'est pas grave : chaque source n'est interrogée que quand c'est son heure.

**a. Créer un jeton GitHub** (un mot de passe limité, réservé à cette tâche)

1. Ouvre ce lien : le nom, la durée et le droit sont déjà remplis.
   https://github.com/settings/personal-access-tokens/new?name=sensor-maps-cron&description=Lancer+la+collecte+de+sensor-maps&target_name=loSCripts&expires_in=none&actions=write
2. Dans « Repository access », choisis **Only select repositories**, puis dans la liste **Selected repositories**, choisis **sensor-maps**.
3. Dans « Permissions », vérifie que **Actions** est sur **Read and write**.
4. Appuie sur **Generate token**. Si une fenêtre de résumé s'ouvre, appuie encore sur **Generate token**.
5. Copie le jeton affiché (il commence par `github_pat_`) : GitHub ne le montre qu'une seule fois.

Ce jeton sert seulement à gérer les passages de collecte de sensor-maps : il ne peut ni modifier le code, ni lire les clés. Tu peux le supprimer quand tu veux sur https://github.com/settings/personal-access-tokens

**b. Créer la tâche sur cron-job.org**

Les noms ci-dessous sont ceux du site en français (relevés dans son code le 25 octobre 2026).

1. Ouvre https://console.cron-job.org/signup, remplis le formulaire et appuie sur **Créer un compte**. Valide l'e-mail reçu, puis connecte-toi avec **Se connecter**.
2. Sur le **Tableau de bord**, appuie sur **Créer un cronjob**.
3. Dans **Titre**, écris `Sensor Maps`.
4. Tout en bas de la page, appuie sur **Import from cURL** (ce bouton reste en anglais).
5. Dans la case « Command or crontab line », colle cette ligne :

```
*/10 * * * * curl -X POST -H "Accept: application/vnd.github+json" -H "Authorization: Bearer COLLE_TON_JETON_ICI" -H "X-GitHub-Api-Version: 2022-11-28" -H "Content-Type: application/json" -d '{"ref":"main"}' https://api.github.com/repos/loSCripts/sensor-maps/actions/workflows/collecte.yml/dispatches
```

6. Dans la case, appuie longtemps sur `COLLE_TON_JETON_ICI` pour le sélectionner, puis choisis **Coller** : ton jeton prend sa place. Garde le mot `Bearer` et l'espace juste avant.
7. Appuie sur **Import**. Le site remplit tout seul l'adresse, l'horaire (toutes les 10 minutes), la méthode, les en-têtes et le corps de la demande.
8. Tout en bas, appuie sur **Créer**. La case **Activer tâche** est déjà cochée.

Si tu as importé la ligne sans y mettre ton jeton : onglet **Avancé**, partie **En-têtes**, trouve la ligne dont la **Clé** est `Authorization`. Dans sa **Valeur**, remplace `COLLE_TON_JETON_ICI` par ton jeton (il doit rester `Bearer`, un espace, puis le jeton). Touche une zone vide de la page pour fermer le clavier : le site n'enregistre la case qu'à ce moment-là. Puis appuie sur **Créer**.

Pour vérifier : ouvre https://github.com/loSCripts/sensor-maps/actions/workflows/collecte.yml. Un nouveau passage doit apparaître toutes les 10 minutes environ.

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

## Appli de test (début de l'étape 2)

Avant de construire la vraie appli de mesure, ce petit test vérifie sur **ton** téléphone ce qu'Android laisse faire quand l'écran est éteint. L'appli essaie de lire la **pression** (baromètre), le **champ magnétique** (magnétomètre) et la **lumière** pendant 10 secondes, de deux façons :

1. **Réveil toutes les 15 minutes** : Android réveille l'appli, sans notification. C'est le plus court intervalle qu'Android accepte.
2. **Après immobilité** : quand le téléphone devient immobile (Android le détecte), l'appli lance un court service avec une notification « Mesure en cours » pendant 10 secondes. Si Android refuse ce service, elle essaie de mesurer directement.

Chaque essai est noté dans un journal : l'heure, l'écran allumé ou éteint, la batterie, et combien de mesures sont arrivées. Rien n'est envoyé nulle part : le journal reste sur le téléphone tant que tu ne le copies pas.

**Installer** (comme pour Sonora) : ouvre https://github.com/loSCripts/sensor-maps/releases/latest/download/sensor-maps-test.apk sur le téléphone. Le téléchargement démarre. Ouvre le fichier, autorise l'installation depuis ton navigateur si Android le demande, puis appuie sur **Installer**.

**Faire le test** :

1. Ouvre l'appli **Test Sensor Maps**. La partie « Capteurs du téléphone » dit si ton téléphone a un baromètre.
2. Appuie sur **Démarrer le test**, puis sur **Autoriser** pour l'activité physique et pour les notifications.
3. Appuie sur **Mesurer maintenant (10 secondes)** : une ligne avec les mesures doit apparaître dans le journal.
4. Utilise ton téléphone normalement, puis laisse-le la nuit, comme d'habitude. Si tu peux, laisse-le aussi au moins 2 heures débranché, écran éteint et posé : c'est là qu'Android est le plus strict.
   Si l'**économie d'énergie** est allumée, éteins-la pour la nuit si tu peux : avec elle, Android bloque presque tout, et le plan prévoit de toute façon que la vraie appli se mette en pause dans ce cas.
5. Le lendemain, ouvre l'appli, appuie sur **Copier le journal**, puis colle-le dans la conversation avec Claude.

**Arrêter** : **Arrêter le test**, puis **Arrêter** pour confirmer. Désinstalle ensuite l'appli si tu veux.

Chaque modification du dossier `appli-test/` fabrique une nouvelle version : la tâche `appli-test.yml` l'essaie sur un téléphone virtuel Android 15, puis la publie. Une nouvelle version s'installe par-dessus l'ancienne. La clé de signature `cle-de-test-publique.jks` est volontairement publique : elle ne protège rien et sert seulement à ces mises à jour. La vraie appli aura sa propre clé, secrète.

## Si quelque chose ne va pas

- **La carte dit « Mesures indisponibles »** : le premier passage n'a pas encore eu lieu. Refais l'étape 2 de la mise en route.
- **Une source est « En panne »** sur la page « Sources et licences » : le message d'erreur y est affiché. C'est souvent passager ; la carte garde les dernières données.
- **GitHub t'envoie un e-mail « Run failed »** : ouvre https://github.com/loSCripts/sensor-maps/actions, touche le passage en rouge, puis l'étape en rouge pour lire l'erreur.
- **GitHub a coupé la collecte** (il le fait après 60 jours sans activité sur un dépôt public, et une tâche coupée ne se lance plus du tout, même par cron-job.org) : la partie « maintien » de la tâche de collecte l'évite. Quand le dernier commit date de 45 jours ou plus, elle écrit la date du jour dans `.github/derniere-activite.txt`. Si ça arrive quand même, ouvre la page de l'étape 2 de la mise en route et appuie sur **Enable workflow**.
- **La collecte ne repasse plus toute seule** (sur la page « Sources et licences », la « dernière réussie » n'avance plus) : connecte-toi sur https://console.cron-job.org, touche la tâche Sensor Maps, puis le menu **Actions** et **Historique des exécutions**. Une erreur 401 veut dire que le jeton GitHub ne marche plus : crée-en un nouveau (étape 4 a de la mise en route). Puis, dans la tâche, onglet **Avancé**, partie **En-têtes**, remplace la **Valeur** de `Authorization` par `Bearer ` suivi du nouveau jeton. Touche une zone vide de la page pour fermer le clavier, puis appuie sur **Sauvegarder**.
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

Testé avec de vrais appels : EMSC, USGS, aéroports (METAR), Sensor.Community, LCSQA et ARCEP, puis la carte dans un navigateur de téléphone simulé. Météo-France est testé sur GitHub avec la vraie clé.

Pas encore testé : OpenAQ (il faut une clé), et la tâche « maintien » (elle ne se lance que si le dépôt reste 45 jours sans commit).

Le minuteur de GitHub n'a jamais lancé la collecte sur ce dépôt (cause inconnue), d'où cron-job.org (étape 4 de la mise en route). Testé pour de vrai : le lancement de 17 h 40 (heure de Paris) envoyé par cron-job.org est arrivé sur GitHub, et la collecte a réussi (séismes, Météo-France et aéroports mis à jour).

Appli de test (26 octobre 2026) : compilée et analysée sans erreur, et essayée automatiquement sur un téléphone virtuel avant chaque publication. Le vrai résultat ne peut venir que de ton téléphone, après une nuit.
