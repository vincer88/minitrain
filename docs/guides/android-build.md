# Guide développeur – Application Android

Ce guide récapitule les tâches de build et de test de l'application Android. Les parcours utilisateurs, états et exigences fonctionnelles sont définis dans la [spécification fonctionnelle Android](../specs/android.md).

## Construire l'application

Depuis la ligne de commande :
```bash
cd android-app
./gradlew assembleDebug   # build debug
./gradlew assembleRelease # build release (signature requise)
```

Depuis Android Studio :
1. Ouvrir le dossier `android-app`.
2. Laisser Gradle se synchroniser.
3. Utiliser *Build > Make Project* ou les actions de build standard.

Les APK sont publiés dans `android-app/app/build/outputs/apk/`.

## Lancer les tests

- Tests unitaires JVM :
  ```bash
  cd android-app
  ./gradlew test
  ```
- Tests instrumentés :
  ```bash
  cd android-app
  ./gradlew connectedDebugAndroidTest
  ```
  Créez un émulateur (ex. Pixel 5, API 33) et démarrez-le avant de lancer la tâche. Les tests Compose sont inclus dans cette suite.

Les captures d'écran générées pendant `TrainControlScreenTest` peuvent être rapatriées via :
```bash
adb pull /sdcard/Android/data/com.minitrain/files/reports/screenshots android-app/app/build/reports/screenshots/
```

## Tests manuels

1. Démarrer un émulateur ou brancher un appareil avec le débogage USB.
2. Installer la build debug :
   ```bash
   cd android-app
   ./gradlew installDebug
   ```
3. Vérifier les transitions d'état (`Disponible`, `En cours`, `Perdu`) et les toasts conformément à la spécification.
