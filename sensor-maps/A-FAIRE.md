# À faire plus tard

Notes gardées dans le dépôt pour ne rien oublier d'une conversation à l'autre.

- **Sonora** (dépôt loSCripts/sonora-wrapper) : sa prochaine fabrication d'APK va échouer. Son fichier `.github/workflows/build.yml` utilise `android-actions/setup-android@v3`, qui cherche le paquet Android « tools », supprimé depuis. Correction : faire comme dans `.github/workflows/appli-test.yml` de ce dépôt, qui installe ce qu'il faut avec le `sdkmanager` déjà présent sur les serveurs de GitHub. Demandé le 26 octobre 2026 : à corriger plus tard, pas maintenant.
- **Clé OpenAQ** : facultative, pas encore ajoutée (voir le README).
- **Appli de test** : lire le journal d'une nuit complète, puis faire ensemble le plan de la vraie appli de mesure.
