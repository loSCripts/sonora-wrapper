# Bibliothèques embarquées dans l'APK Sonora

Depuis la v2.5, l'APK embarque NewPipeExtractor pour télécharger un titre YouTube depuis le téléphone. Avant, elle n'embarquait aucune bibliothèque.

| Bibliothèque | Version | Licence |
|---|---|---|
| [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) | v0.26.5 | **GNU GPL v3 ou ultérieure** |
| [Rhino](https://github.com/mozilla/rhino) (rhino, rhino-engine) | 1.8.1 | Mozilla Public License 2.0 |
| [jsoup](https://jsoup.org) | 1.22.2 | MIT |
| [nanojson](https://github.com/TeamNewPipe/nanojson) (copie TeamNewPipe) | e9d656d | MIT ou Apache 2.0, au choix |
| [Protocol Buffers](https://github.com/protocolbuffers/protobuf) (protobuf-javalite) | 4.35.1 | BSD 3 clauses |
| JSR 305 (annotations, findbugs) | 3.0.2 | Apache 2.0 |
| desugar_jdk_libs (Google, compilation) | 2.0.4 | GPL v2 avec exception « classpath » |

La liste vient de `gradle :app:dependencies --configuration releaseRuntimeClasspath`, et les licences des fichiers POM publiés.

## Ce qu'implique la GPL v3

Une APK qui embarque NewPipeExtractor est une œuvre dérivée au sens de la GPL v3. **Quiconque reçoit l'APK doit pouvoir obtenir son code source complet sous GPL v3**, c'est-à-dire ce dépôt. Deux façons de le respecter :

- **rendre ce dépôt public** ;
- ou **joindre une offre écrite** de fournir le code source à qui le demande. Par exemple une ligne dans la page d'installation de l'APK, avec une adresse de contact.

Le site (`sonora-v1/`) n'est pas concerné. Il ne contient pas NewPipeExtractor : il appelle seulement l'application par le pont `SonoraNative`.
