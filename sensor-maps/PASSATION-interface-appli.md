# Sensor Maps : identité et interface de l'appli

Document de passation (26 octobre 2026). Il contient seulement ce qui sert à créer l'identité de Sensor Maps et la maquette de l'interface de l'appli que les gens installeront. Lis-le en entier.

## 1. Ta mission

1. **L'identité** de Sensor Maps : une histoire, un logo, une palette de couleurs, une typographie, un ton. Elle servira à l'appli, au site (la carte) et, plus tard, à des vidéos : elle doit aussi fonctionner en mouvement.
2. **La maquette de l'interface** de l'appli : seulement l'interface, **rien de fonctionnel**. Belle, épurée, soignée.

Ordre conseillé : d'abord l'histoire et 3 pistes de logo, l'utilisateur choisit ; puis la palette et la typographie ; puis les écrans.

## 2. L'utilisateur et ses règles (elles comptent beaucoup)

- Il écrit en français, souvent à la dictée vocale : le texte contient des erreurs de transcription. Interprète avec bon sens.
- Réponds en **français**, avec des **mots simples**, de façon directe et courte.
- **Jamais de tiret long (U+2014) ni de tiret moyen (U+2013)**, nulle part : réponses, code, textes d'interface. Pour lui, c'est la marque d'une IA. Vérifie chaque livraison avec un petit script.
- Il travaille **uniquement depuis un téléphone Android** (Samsung), sans ordinateur. Chaque livraison doit pouvoir **se voir sur le téléphone**, et il veut aussi un **ZIP**. S'il y a des commandes de terminal, une seule à la fois.
- Il préfère les **fichiers HTML uniques** (HTML, CSS et JavaScript dans un seul fichier).
- Il veut un plan correct avant de construire. Quand il valide, il dit « go ». Pour le reste, prends des choix par défaut raisonnables et signale-les, plutôt que de poser beaucoup de questions.
- **Il déteste le texte inutile.** Il a déjà dit d'une maquette : « il y a beaucoup de textes inutiles, purifie l'interface en tant que designer ».
- **Aucune donnée fictive présentée comme réelle.** Dans une maquette, un petit badge discret « Maquette » suffit.

## 3. Ses mots pour cette mission

« Une interface belle, épurée, qui explique tout, qui prend les mesures, où on peut faire des dons. Simple, épuré, pas de texte inutile qui décrit des choses pour rien. Si l'utilisateur installe l'application, c'est qu'il sait à quoi ça sert : le décrire un tout petit peu, mais pas en plein milieu, sinon c'est moche. Vraiment soigné. Une identité avec un logo, une histoire, une palette de couleurs. Plus tard, il y aura des vidéos. Il faut que ce soit simple. »

## 4. Le projet en bref

- **Sensor Maps** : une carte publique et ouverte de mesures de l'environnement. Les téléphones ont déjà des capteurs (pression de l'air, champ magnétique, lumière, micro, signal mobile) : beaucoup de téléphones ordinaires, qui mesurent quelques secondes au bon moment, peuvent rendre visible ce qu'on ne voit pas autour de nous. Les données sont ouvertes à tous.
- Le nom **Sensor Maps** est choisi par l'utilisateur (dépôt, site et appli l'utilisent). Ne le change pas sans son accord.
- **Étape 1, faite et en ligne** : la carte https://loscripts.github.io/sensor-maps/ rassemble des sources publiques récentes (séismes, pression et température, qualité de l'air, débit mobile, bruit) et se met à jour toute seule toutes les 10 minutes. Dépôt public : https://github.com/loSCripts/sensor-maps
- **Étape 2, en cours** : l'appli de mesure. Une petite appli de test (dossier `appli-test/`, réservée aux essais, pas pour le public) vérifie ce qu'Android permet écran éteint. Son résultat changera la façon de mesurer, pas l'interface.
- Premier constat sur le téléphone de l'utilisateur (Samsung Galaxy A16, Android 16) : **pas de baromètre**, boussole présente. Beaucoup de téléphones n'ont pas tous les capteurs : l'interface doit afficher sobrement « pas sur ce téléphone ».

## 5. Ce que l'appli fait (à rendre clair, sans long texte)

- Des **mesures courtes** (10 à 20 secondes), jamais en continu : pression, champ magnétique, lumière, bruit (3 secondes de micro, niveau calculé sur le téléphone, aucun son gardé), signal mobile.
- Elle mesure au bon moment : téléphone posé et immobile, ou quand il arrive dans un nouveau quartier.
- Elle envoie peu, par petits paquets sur Wi-Fi : seulement la zone et la valeur, jamais le trajet ni l'identité.
- La carte publique affiche les résultats par zone, et seulement quand au moins **5 appareils** ont mesuré dans la zone.
- Valeurs **brutes** ou **corrigées** : chaque modèle de téléphone a sa correction.
- **Batterie** : l'utilisateur choisit un budget (« jusqu'à 0,5 %, 1 % ou 2 % par jour »). L'appli se met en pause toute seule si la batterie est sous 30 %, en économie d'énergie, si le téléphone chauffe ou si le budget du jour est dépensé. Il peut aussi faire pause lui-même.
- **Mode nuit** (facultatif) : lancé par l'utilisateur, téléphone en charge et posé.
- **Petites contributions volontaires**, rares et seulement pour ceux qui le veulent, par exemple : « il pleut près de chez toi, pose ton téléphone dehors une minute ».
- **Soutenir** : des dons, et partager l'appli. Pas de réseau publicitaire, aucun traceur.

## 6. Principes à ne jamais casser

- Formulations honnêtes : « détecter un séisme en cours », jamais « prédire ». « Repérer des anomalies magnétiques », jamais « voir à travers les murs ».
- Aucun capteur ne sert à identifier une personne ou à deviner une information privée. Exclu définitivement.
- **Jamais de « mode station »** : pas de téléphone dédié posé en fixe pour mesurer. Refusé définitivement par l'utilisateur, ne le propose sous aucune forme.
- Les données sont ouvertes et publiques.

## 7. Écrans probables (à adapter, ce n'est pas un design imposé)

- **Premier lancement** : 2 ou 3 écrans au plus : ce que fait l'appli en une phrase, les autorisations (activité physique, notifications, position approximative) et la promesse de vie privée.
- **Accueil** : l'état (en marche ou en pause), ce qui a été mesuré aujourd'hui, le budget de batterie.
- **Mes capteurs** : ceux du téléphone, présents ou absents, avec les dernières valeurs, brut ou corrigé.
- **Carte** : un aperçu de la carte publique autour de soi.
- **Soutenir** : dons et partage.
- **Réglages** : budget de batterie, pause, mode nuit, petites contributions, vie privée.

## 8. Livrables attendus

1. **Une page « Identité »** (HTML unique) : l'histoire en quelques lignes, le logo en SVG (symbole et nom), la palette avec le rôle de chaque couleur, en clair et en sombre, et des contrastes lisibles, puis la typographie et le ton. Le logo doit être original et prêt pour Android : icône adaptative (108 dp, zone visible de 66 dp au centre), version monochrome (icônes thématiques d'Android 13 et plus), petite icône blanche de notification, icône de 512 px pour Google Play.
2. **La maquette interactive de l'appli** (HTML unique, au format téléphone) : on passe d'un écran à l'autre, les valeurs sont simulées, avec un badge « Maquette ». Penser aux bords d'écran d'Android 15 et plus (l'appli s'affiche sous les barres du système).
3. **Un ZIP** avec tout.

Pour voir sur le téléphone : un artifact, ou bien le dossier `web/maquette-appli/` du dépôt. Tout ce qui est dans `web/` est publié automatiquement sur le site, ici à l'adresse https://loscripts.github.io/sensor-maps/maquette-appli/

## 9. Plus tard (ne pas faire maintenant)

- La vraie appli sera probablement une PWA avec Capacitor pour lire les capteurs, compilée par GitHub Actions : une maquette en HTML et CSS propre pourra donc resservir.
- Le site (la carte) prendra la même identité.
- Peut-être une page séparée de liens sponsorisés, sans traceur : question encore ouverte.
- Des vidéos de présentation.

## 10. Si tu as accès au dépôt

Travaille dans un nouveau dossier (par exemple `interface/` ou `web/maquette-appli/`). Ne touche pas à `collector/`, `appli-test/`, `.github/workflows/` ni aux pages de la carte dans `web/` : ils tournent en ce moment. `A-FAIRE.md` liste ce qui reste à faire plus tard.
