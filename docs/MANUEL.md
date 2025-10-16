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
- **Détection de perte** : deux seuils configurables encadrent désormais la sécurité des trains.
  - `T₁` (*fail-safe ramp*) : par défaut 150 ms. Dès que l’écart entre le `timestamp` le plus récent et l’horloge locale dépasse `T₁`, une rampe d’arrêt progressive est déclenchée. La télémétrie bascule `lights_source` sur `fail_safe` et force l’affichage **rouge bilatéral fixe**.
  - `T₂` (*pilot release*) : par défaut 5 s. Si aucune commande valide n’est reçue pendant `T₂`, le contrôleur considère que le pilote ne maintient plus la session. Le train est alors relâché automatiquement (voir § 2.4) et un nouvel opérateur peut le sélectionner.
- **Rampe d’arrêt** : la durée de la rampe est configurable (1000 ms par défaut). Pendant la rampe, `target_speed` est décrémentée linéairement jusqu’à 0 avec maintien de la dernière direction connue, puis la direction repasse à neutre. La logique d’éclairage automatique continue de s’appliquer, mais l’état lumineux reste **rouge** tant que `fail_safe` est actif.

### 2.3 Logique d’éclairage et télémétrie

- **Connexion active sans cabine sélectionnée** : feux rouges bilatéraux permanents et état « disponible » publié.
- **Cabine sélectionnée en marche avant** : feu blanc allumé côté cabine, feu rouge opposé.
- **Cabine sélectionnée en marche arrière** : inversion des couleurs (blanc vers l’arrière réel de la rame). La télémétrie renvoie l’état courant (`lights_state`) ainsi que la source de décision (`auto`, `override`, `fail_safe`).
- **Fail-safe ramp (`T₁`)** : dès l’activation, les feux restent **rouges fixes** même si une direction avait été demandée. La télémétrie expose `fail_safe = true` et inclut `fail_safe_elapsed_ms` pour permettre à l’application de représenter la progression de la rampe.
- **Pilot release (`T₂`)** : lorsque la libération automatique est déclenchée, les feux passent en **rouge clignotant** jusqu’à ce qu’un opérateur reprenne la main ou qu’un ordre de parcage manuel soit appliqué. La session courante est marquée comme expirée.
- **Overrides** : lorsqu’un bit `lights_override` est actif, le firmware publie l’état forcé et la télémétrie indique `override`. À la désactivation, la logique automatique reprend dès la prochaine commande valide.
- **Synchronisation** : chaque message de télémétrie inclut `session_id`, `seq`, `timestamp` et reflète la direction/ vitesse réellement appliquées pour permettre la validation client.

### 2.4 Disponibilité d’un train

Un train est déclaré **disponible** dans les cas suivants :

1. Aucune cabine n’est sélectionnée (état initial après démarrage ou après un `pilot release`).
2. La durée sans commandes dépasse `T₂` : le firmware force la libération du pilote et remet l’état d’éclairage en rouge clignotant pour signaler que la rame attend un nouvel opérateur.
3. Un opérateur déclenche manuellement l’action *Relâcher* dans l’application Android ou sur la cabine.

Tant que la télémétrie indique `fail_safe = true`, la rame n’est pas disponible même si la session a expiré : l’application doit attendre la fin de la rampe (progression à 100 %) avant d’autoriser un nouveau démarrage.

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
   *Depuis la révision actuelle, le code vérifie à la compilation que `CONFIG_IDF_TARGET_ESP32` est défini : si une autre cible est
   sélectionnée, le build échoue explicitement pour éviter de flasher le mauvais SoC.*
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

## 5. Mode d’emploi – Vue générale multi-train

La page d’accueil de l’application présente désormais **la liste de toutes les rames connues** ainsi que leur état en temps réel.

### 5.1 Ajouter ou retirer un train

- **Ajouter** : utiliser le bouton *Ajouter un train*. Une boîte de dialogue demande l’identifiant de rame (UUID), l’alias affiché et l’URL du canal de commandes. Dès validation, le train apparaît dans la liste avec l’état « Connexion en cours ».
- **Retirer** : via le menu *⋮* de chaque carte, sélectionner *Supprimer*. La suppression coupe la session WebSocket, efface les secrets associés et retire le train de la vue générale.
- **Réordonner** : un glisser-déposer permet de prioriser les rames les plus critiques (ordre persistant dans les préférences locales).

### 5.2 Indicateurs de connexion

Chaque carte de train affiche un **pictogramme circulaire** indiquant la santé de la connexion :

- 🟢 `Connecté` : la dernière télémétrie date de moins de `T₁`.
- 🟠 `Fail-safe` : absence de commandes ayant déclenché la rampe (`T₁`). Une jauge circulaire affiche la progression (`fail_safe_elapsed_ms / rampDuration`).
- 🔴 `Relâché` : aucun message reçu depuis `T₂`. La session a été libérée et le train est en attente d’un nouveau pilote.
- ⚪ `Déconnecté` : échec réseau ou suppression volontaire.

### 5.3 Indicateurs de disponibilité

Une **pastille textuelle** complète l’icône :

- `Disponible` : aucun pilote n’est sélectionné (état initial, relâchement automatique `T₂` ou relâchement manuel). L’app permet immédiatement de se connecter.
- `Réservé` : un opérateur actif a pris la main (télémétrie `fail_safe = false` et session valide).
- `Verrouillé (fail-safe)` : la rampe est active (`T₁`) ; l’application affiche l’action *Attendre la fin de la rampe* et empêche toute nouvelle commande.

Lorsque `T₂` est atteint, la pastille repasse automatiquement à `Disponible`, les feux virent au rouge clignotant (cf. § 2.3) et la carte affiche un bouton *Reprendre ce train*. Cette cohérence garantit que le flux multi-train reste aligné avec les seuils décrits plus haut.

## 6. Intégration continue (optionnel)

- Ajoutez un pipeline (GitHub Actions, GitLab CI) qui exécute les commandes principales :
  - Firmware : `cmake -S firmware -B firmware/build`, `cmake --build firmware/build`, `ctest --test-dir firmware/build`.
  - Android : `./gradlew test` et `./gradlew lint`.
  - Pour les déploiements, déclenchez des jobs manuels (`./gradlew publish` ou scripts `idf.py flash`).

## 7. Résolution de problèmes

- Nettoyage CMake : supprimez `firmware/build/` puis relancez la configuration.
- Nettoyage Gradle : `./gradlew clean`.
- Emulateur lent : désactivez les animations et activez l’accélération matérielle (Intel HAXM/Hypervisor). Redémarrez l’AVD si des erreurs `INSTALL_FAILED` apparaissent.
- Flash ESP32 : vérifiez les permissions (`sudo usermod -aG dialout $USER`) et réduisez la vitesse avec `--flash_freq` si la communication échoue.

## 8. Ressources complémentaires

- Documentation ESP-IDF : https://docs.espressif.com/
- Documentation Android Developer : https://developer.android.com/
- Guides Gradle Android : https://developer.android.com/studio/build

Ce manuel doit être complété et adapté selon l’évolution du projet (nouvelles cibles matérielles, pipelines CI/CD spécifiques, etc.).
