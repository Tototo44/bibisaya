# BiBisaya

Une appli Android pour apprendre le cebuano (bisaya), basée sur les cours de Thomas.

Chaque jour, 3 séances de 10 exercices (environ 4 minutes) :

- **le matin** : du vocabulaire ;
- **le midi** : des phrases simples ;
- **le soir** : un jour sur deux du vocabulaire, l'autre des phrases compliquées.

Un rappel arrive à l'heure de chaque séance. Les mots ratés reviennent plus souvent, les mots bien connus de plus en plus rarement. Points, série de jours, badges et une route de jeepney par ligne pour voir sa progression.

Tout reste sur le téléphone : pas de compte, pas besoin d'internet.

## Installer

Sur le téléphone, ouvre ce lien dans Chrome :

**https://github.com/Tototo44/bibisaya/releases/latest/download/BiBisaya.apk**

Si Chrome prévient que le fichier peut être dangereux, choisis « Télécharger quand même », puis ouvre le fichier et accepte l'installation. Une nouvelle version s'installe par-dessus l'ancienne, sans perdre la progression.

## Pour les curieux

- Kotlin, Android 8 et plus, sans bibliothèque externe.
- Le contenu des cours est dans `app/src/main/assets/contenu.txt` (une ligne par mot ou par phrase).
- Le moteur (séances, révisions, points, badges) est dans `app/src/main/java/com/thomas/bibisaya/engine/` et vérifié par `./gradlew testReleaseUnitTest`.
- À chaque envoi sur GitHub, l'appli est construite et publiée automatiquement (voir `.github/workflows/build.yml`), et des captures d'écran sont prises sur un émulateur (branche `captures`).
- Police : Bricolage Grotesque (licence SIL Open Font, voir `app/src/main/assets/OFL-bricolage.txt`).
