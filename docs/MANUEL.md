# Manuel de construction, test et déploiement

Ce document décrit, pour chaque composant du projet **minitrain**, la manière de le construire, de le tester (y compris via un émulateur Android) et de le déployer.

## 1. Pré-requis généraux

- Git et un shell POSIX (Linux, macOS ou WSL recommandé).
- CMake ≥ 3.16 et un compilateur C++17 (g++, clang++ ou toolchain ESP-IDF).
- Java 17+, le SDK Android, Android Studio (ou les outils en ligne de commande) et Gradle.
- Python 3 (optionnel) si vous utilisez des scripts de support.

> **Astuce** : pour exécuter les commandes Gradle sans installation préalable, utilisez le wrapper fourni : `./gradlew` (Linux/macOS) ou `gradlew.bat` (Windows).

## 2. Architecture de communication temps réel

### 2.1 Canal de commandes périodiques

- **Support recommandé** : WebSocket binaire ou QUIC-unidirectionnel selon la plateforme. Les implémentations pilotes utilisent un WebSocket TLS (wss://) afin de maximiser la compatibilité mobile et embarquée.
- **Cadence nominale** : 50 Hz (période de 20 ms). Les composants doivent supporter une dégradation contrôlée jusqu’à 10 Hz en cas de congestion.
- **Format de trame** (binaire little-endian) :
  | Champ        | Type         | Description |
  |--------------|--------------|-------------|
  | `session_id` | UUID 128 bits| Identifiant logique de la session contrôle/commande active. |
  | `seq`        | uint32       | Compteur monotone des paquets de commandes. |
  | `timestamp`  | int64 (µs)   | Horodatage Unix microseconde du calcul de la commande. |
  | `target_speed` | float32 (m/s) | Vitesse linéaire demandée. |
  | `direction`  | enum8        | `0` = neutre, `1` = avant, `2` = arrière. |
  | `lights_override` | bitfield8 | Bits `0-1` : forcage blanc gauche/droite, `2-3` : forçage rouge, `7` : télémétrie seulement. `0x00` signifie « logique automatique ». |
  | `aux_payload_len` | uint16    | Longueur de la charge utile optionnelle. |
  | `aux_payload` | bytes       | Charge utile (ex. configuration de rampes, diagnostics). |

Les réponses télémétriques réutilisent `session_id` et `seq` pour corréler commandes et observations.

### 2.2 Sécurité et résilience

- **Chiffrement & authentification** : toutes les connexions sont chiffrées (TLS 1.3 minimum). L’authentification mutuelle est réalisée via certificats clients pour la cabine et via OAuth2 mTLS pour les applications mobiles. Les jetons expirent après 12 heures et doivent être renégociés hors bande.
- **Détection de perte** : un seuil unique (par défaut 150 ms) est configurable côté firmware et côté client. Lorsque l’écart entre le `timestamp` le plus récent et l’horloge locale dépasse ce seuil, une rampe d’arrêt progressive est déclenchée.
- **Rampe d’arrêt** : la durée de la rampe est configurable (1000 ms par défaut). Pendant la rampe, `target_speed` est décrémentée linéairement jusqu’à 0 avec maintien de la dernière direction connue, puis la direction repasse à neutre. Les feux sont forcés à l’état sécurité (rouges bilatéraux) tant que la rampe est active.

### 2.3 Logique d’éclairage et télémétrie

- **Connexion active sans cabine sélectionnée** : feux rouges bilatéraux permanents.
- **Cabine sélectionnée en marche avant** : feu blanc allumé côté cabine, feu rouge opposé.
- **Cabine sélectionnée en marche arrière** : inversion des couleurs (blanc vers l’arrière réel de la rame). La télémétrie renvoie l’état courant (`lights_state`) ainsi que la source de décision (`auto`, `override`, `fail_safe`).
- **Overrides** : lorsqu’un bit `lights_override` est actif, le firmware publie l’état forcé et la télémétrie indique `override`. À la désactivation, la logique automatique reprend dès la prochaine commande valide.
- **Synchronisation** : chaque message de télémétrie inclut `session_id`, `seq`, `timestamp` et reflète la direction/ vitesse réellement appliquées pour permettre la validation client.

## 3. Firmware C++ (ESP32)

### 3.1 Construction

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

### 3.2 Tests

- Tests unitaires (ctest) :
  ```bash
  ctest --test-dir firmware/build
  ```
  (Assurez-vous d’avoir compilé au préalable.)
- Tests sur cible simulée : exécutez l’application console générée (par exemple `./firmware/build/simulateur`) pour rejouer des scénarios.

### 3.3 Déploiement sur ESP32 réel

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

> **Note firmware** : les versions existantes reposant sur HTTP périodique doivent migrer vers le canal temps réel ci-dessus. Prévoir une couche d’adaptation temporaire (WebSocket client embarqué) pour les versions hybrides, puis retirer l’ancienne pile HTTP dès que la migration est validée (voir plan §4.4).

## 4. Application Android

### 4.1 Construction

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

### 4.2 Tests

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

### 4.3 Déploiement

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

### 4.4 Plan de migration vers le protocole flux

1. **Client Android** : introduire une couche réseau temps réel (WebSocket/QUIC) parallèlement à l’API HTTP actuelle. Ajouter des tests unitaires validant le parsing des trames et des tests instrumentés simulant des pertes/rampe.
2. **Simulateur** : mettre à jour les scénarios de test pour publier les trames de commandes et vérifier la cohérence télémétrie/éclairage. Conserver des fixtures HTTP uniquement pour la rétrocompatibilité courte durée.
3. **Intégration continue** : ajouter une étape de tests end-to-end utilisant le nouveau protocole (mock serveur) et un test de non-régression s’assurant que l’ancien mode HTTP est explicitement marqué obsolète.
4. **Retrait HTTP** : planifier une version mineure annonçant la fin du support HTTP, fournir une période de double pile de deux releases, puis retirer les endpoints HTTP et les adapters temporaires.

## 5. Intégration continue (optionnel)

- Ajoutez un pipeline (GitHub Actions, GitLab CI) qui exécute les commandes principales :
  - Firmware : `cmake -S firmware -B firmware/build`, `cmake --build firmware/build`, `ctest --test-dir firmware/build`.
  - Android : `./gradlew test` et `./gradlew lint`.
  - Pour les déploiements, déclenchez des jobs manuels (`./gradlew publish` ou scripts `idf.py flash`).

## 6. Résolution de problèmes

- Nettoyage CMake : supprimez `firmware/build/` puis relancez la configuration.
- Nettoyage Gradle : `./gradlew clean`.
- Emulateur lent : désactivez les animations et activez l’accélération matérielle (Intel HAXM/Hypervisor). Redémarrez l’AVD si des erreurs `INSTALL_FAILED` apparaissent.
- Flash ESP32 : vérifiez les permissions (`sudo usermod -aG dialout $USER`) et réduisez la vitesse avec `--flash_freq` si la communication échoue.

## 7. Ressources complémentaires

- Documentation ESP-IDF : https://docs.espressif.com/
- Documentation Android Developer : https://developer.android.com/
- Guides Gradle Android : https://developer.android.com/studio/build

Ce manuel doit être complété et adapté selon l’évolution du projet (nouvelles cibles matérielles, pipelines CI/CD spécifiques, etc.).
