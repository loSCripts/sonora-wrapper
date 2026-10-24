# distribution/

Fichiers publiés par le site `sonora-musique.vercel.app`, récupérés par sa
construction Vercel (empreintes SHA-1 vérifiées avant publication) :

- `sonora.apk` : l'application servie par le bouton « Installer
  l'application » de l'accueil, à l'adresse `/sonora.apk`. Signée avec la clé
  fixe Sonora (empreinte SHA-256 `a4a30438…0ff5`) : les versions suivantes
  s'installeront par-dessus. Version 2.6 (compilation « release », sans
  mode débogage ; pont protégé par un jeton). Le téléchargement YouTube se
  fait dans l'application elle-même, par tranches et avec la même connexion
  que l'extraction ; un refus de YouTube déclenche une nouvelle extraction
  puis un second essai. Elle embarque NewPipeExtractor, sous GPL v3 : voir
  `LICENCES-TIERCES.md` à la racine du dépôt.
- `site-editions.json` : les modifications du site (v118) appliquées aux
  fichiers v114 déjà en ligne, octet pour octet, avec l'empreinte attendue
  avant et après chaque fichier.
