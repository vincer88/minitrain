# Manuel de construction, test et déploiement

Ce document décrit, pour chaque composant du projet **minitrain**, la manière de le construire, de le tester (y compris via un émulateur Android) et de le déployer.

## 1. Pré-requis généraux

- Git et un shell POSIX (Linux, macOS ou WSL recommandé).
- CMake ≥ 3.16 et un compilateur C++17 (g++, clang++ ou toolchain ESP-IDF).
- Java 17+, le SDK Android, Android Studio (ou les outils en ligne de commande) et Gradle.
- Python 3 (optionnel) si vous utilisez des scripts de support.

> **Astuce** : pour exécuter les commandes Gradle sans installation préalable, utilisez le wrapper fourni : `./gradlew` (Linux/macOS) ou `gradlew.bat` (Windows).

## 2. Firmware C++ (ESP32)

### 2.1 Construction

1. Configurer la build avec CMake :
   ```bash
   cmake -S firmware -B firmware/build \
         -DCMAKE_BUILD_TYPE=Release
   ```
2. Compiler :
   ```bash
   cmake --build firmware/build
   ```
   Les binaires se trouvent dans `firmware/build/`.

### 2.2 Tests

- Tests unitaires (ctest) :
  ```bash
  ctest --test-dir firmware/build
  ```
  (Assurez-vous d’avoir compilé au préalable.)
- Tests sur cible simulée : exécutez l’application console générée (par exemple `./firmware/build/simulateur`) pour rejouer des scénarios.

### 2.3 Déploiement sur ESP32 réel

1. Installer le SDK [ESP-IDF](https://docs.espressif.com/).
2. Configurer la toolchain (`idf.py set-target esp32`).
3. Reconfigurer le projet si nécessaire :
   ```bash
   idf.py -C firmware menuconfig
   ```
4. Compiler et flasher :
   ```bash
   idf.py -C firmware build
   idf.py -C firmware flash
   idf.py -C firmware monitor
   ```
   Ajustez `--port` selon le port série de votre carte.

## 3. Application Android

### 3.1 Construction

#### En ligne de commande

```bash
cd android-app
./gradlew assembleDebug   # build debug
./gradlew assembleRelease # build release (signature requise)
```

Les APK sont générés dans `android-app/app/build/outputs/apk/`.

#### Depuis Android Studio

1. Ouvrir le dossier `android-app`.
2. Laisser l’IDE synchroniser Gradle.
3. Utiliser *Build > Make Project* ou les boutons de build habituels.

### 3.2 Tests

#### Tests unitaires JVM

```bash
cd android-app
./gradlew test
```

#### Tests instrumentés sur émulateur

1. Créer un émulateur via l’AVD Manager (par ex. Pixel 5, API 33).
2. Démarrer l’émulateur.
3. Lancer les tests instrumentés :
   ```bash
   cd android-app
   ./gradlew connectedAndroidTest
   ```
   Android Studio propose aussi le bouton *Run tests in device*.

#### Tests manuels

1. Démarrer l’émulateur ou connecter un appareil USB avec le débogage activé.
2. Lancer
   ```bash
   cd android-app
   ./gradlew installDebug
   ```
   ou utiliser le bouton *Run* d’Android Studio pour installer et lancer l’application.

### 3.3 Déploiement

- **Version de test interne (Firebase App Distribution / Play Console)**
  1. Générez un APK ou App Bundle `release` signé :
     ```bash
     ./gradlew bundleRelease
     ```
  2. Téléchargez l’AAB sur la Play Console (test interne/fermé) ou via Firebase App Distribution.
- **Publication sur le Play Store**
  1. Assurez-vous que toutes les dépendances sont en release, que `minifyEnabled` est configuré, et que les tests CI passent.
  2. Fournissez les éléments obligatoires (icônes, screenshots, politique de confidentialité).
  3. Téléchargez l’AAB `bundleRelease`, puis suivez le flux de validation Google.

## 4. Intégration continue (optionnel)

- Ajoutez un pipeline (GitHub Actions, GitLab CI) qui exécute les commandes principales :
  - Firmware : `cmake -S firmware -B firmware/build`, `cmake --build firmware/build`, `ctest --test-dir firmware/build`.
  - Android : `./gradlew test` et `./gradlew lint`.
  - Pour les déploiements, déclenchez des jobs manuels (`./gradlew publish` ou scripts `idf.py flash`).

## 5. Résolution de problèmes

- Nettoyage CMake : supprimez `firmware/build/` puis relancez la configuration.
- Nettoyage Gradle : `./gradlew clean`.
- Emulateur lent : désactivez les animations et activez l’accélération matérielle (Intel HAXM/Hypervisor). Redémarrez l’AVD si des erreurs `INSTALL_FAILED` apparaissent.
- Flash ESP32 : vérifiez les permissions (`sudo usermod -aG dialout $USER`) et réduisez la vitesse avec `--flash_freq` si la communication échoue.

## 6. Ressources complémentaires

- Documentation ESP-IDF : https://docs.espressif.com/
- Documentation Android Developer : https://developer.android.com/
- Guides Gradle Android : https://developer.android.com/studio/build

Ce manuel doit être complété et adapté selon l’évolution du projet (nouvelles cibles matérielles, pipelines CI/CD spécifiques, etc.).
