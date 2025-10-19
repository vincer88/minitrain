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
  - `T₁` (*fail-safe ramp*) : par défaut 150 ms. Dès que l’écart entre le `timestamp` le plus récent et l’horloge locale dépasse `T₁`, la commande moteur est immédiatement coupée tandis que la direction active est maintenue uniquement jusqu’à sa neutralisation.
  - `T₂` (*pilot release*) : par défaut 5 s. Si aucune commande valide n’est reçue pendant `T₂`, le contrôleur considère que le pilote ne maintient plus la session. Le train est alors relâché automatiquement (voir § 2.4) et un nouvel opérateur peut le sélectionner.
- **Rampe d’arrêt** : la durée de la rampe est configurable (1000 ms par défaut). Elle ne pilote plus une décroissance progressive de la vitesse, mais définit la fenêtre pendant laquelle la direction est maintenue avant de repasser automatiquement à neutre.

### 2.3 Logique d’éclairage et télémétrie

- **Connexion active sans cabine sélectionnée** : feux rouges bilatéraux permanents et état « disponible » publié.
- **Cabine sélectionnée en marche avant** : feu blanc allumé côté cabine, feu rouge opposé.
- **Cabine sélectionnée en marche arrière** : inversion des couleurs (blanc vers l’arrière réel de la rame). La télémétrie renvoie l’état courant (`lights_state`) ainsi que la source de décision (`auto`, `override`, `fail_safe`).
- **Fail-safe ramp (`T₁`)** : dès l’activation, la propulsion est coupée immédiatement. La télémétrie expose `fail_safe = true` et publie `fail_safe_progress`, qui reflète la temporisation interne jusqu’à la neutralisation de la direction.
- **Pilot release (`T₂`)** : lorsque la libération automatique est déclenchée, les feux conservent l’état déterminé par la logique automatique (sauf override explicite). La session courante est marquée comme expirée.
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
   ./gradlew connectedDebugAndroidTest
   ```
   - `connectedDebugAndroidTest` exécute l’intégralité de la suite instrumentée (tests réseau et tests Compose `TrainControlScreenTest` / `TrainSelectionScreenTest`). Il n’existe pas de tâche `connectedComposeDebugAndroidTest` distincte : les tests Compose sont inclus dans ce jeu unique.

   Les captures d’écran générées pendant `TrainControlScreenTest` sont enregistrées sur l’appareil dans `Android/data/com.minitrain/files/reports/screenshots`. Pour les inclure dans une revue ou un artefact CI, rapatriez-les vers `android-app/app/build/reports/screenshots/` :

   ```bash
   adb pull /sdcard/Android/data/com.minitrain/files/reports/screenshots android-app/app/build/reports/screenshots/
   ```

> **Note** : la suite instrumentée Compose utilise un endpoint fictif (`https://example.com/video.m3u8`) et ne lit pas de propriété `cab.video.previewUrl`. Aucun flux vidéo réel n’est requis pour exécuter les tests.

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

## 5. Mode d’emploi – Parcours opérateur multi-train

La page d’accueil présente une **liste simple** des rames enregistrées. Chaque item affiche alias, disponibilité et deux boutons texte (*Activer*, *Détails*). Aucune carte graphique n’est requise : la priorité est la rapidité d’activation sur tablette ou pupitre industriel.

### 5.1 Gestion des trains

- **Ajouter** : le bouton *Ajouter un train* crée immédiatement une rame de démonstration avec un identifiant généré. Aucun dialogue n’est présenté ; l’alias peut être ajusté ensuite dans l’interface.
- **Contrôler** : l’action *Contrôler* passe la rame en mode actif et ouvre la surimpression cabine.
- **Supprimer** : l’action *Supprimer* retire l’élément de la liste et libère les ressources associées.

> Aucun glisser-déposer ni personnalisation avancée n’est proposé pour l’instant ; l’ordre reste celui d’ajout.

### 5.2 Indicateurs de connexion et disponibilité

Chaque ligne de la liste comporte une pastille texte qui bascule automatiquement entre trois statuts :

- `Disponible` : aucune session n’est active, l’action *Contrôler* est accessible.
- `En cours` : la rame est contrôlée par l’opérateur local et la surimpression est ouverte.
- `Perdu` : la dernière commande remonte à plus de `T₂` secondes ou la connexion WebSocket est tombée.

Un toast prévient l’opérateur lorsqu’un train repasse à l’état `Disponible` après une perte de connexion. Aucun code couleur ni jauge n’est actuellement affiché.

### 5.3 Activation de la cabine

1. Appuyer sur *Contrôler* affiche la surimpression cabine, un panneau semi-transparent couvrant l’écran.
2. Un **slider horizontal** ajuste la vitesse. La valeur courante est reflétée immédiatement dans la télémétrie.
3. Deux **puces** `Direction` et `Cabine` permettent d’inverser le sens de marche et d’alterner l’angle de vue associé.
4. Le bandeau supérieur confirme la connexion. Un bouton *Fermer* restaure la liste sans autre dialogue.

### 5.4 Déroulé utilisateur

1. **Sélection** : utiliser *Ajouter un train* si nécessaire, puis choisir une rame `Disponible`.
2. **Contrôle** : cliquer sur *Contrôler* ouvre la surimpression et réserve la rame.
3. **Pilotage** : régler la vitesse via le slider et modifier le sens avec la puce `Direction`. Les changements sont appliqués sans étape intermédiaire.
4. **Fin de session** : fermer la surimpression ramène à la liste. En cas de déconnexion (`Perdu`), la rame redevient `Disponible` automatiquement dès que la liaison revient.

> La personnalisation avancée de la cabine (habillages dédiés, overlays spécifiques) est planifiée pour une mise à jour ultérieure.

### 5.5 Configuration des visuels cabine

La cabine s’appuie aujourd’hui sur un habillage générique commun à toutes les rames. Aucun fichier `cabs.json`, overlay dédié ou flux vidéo spécifique n’est nécessaire.

> Une personnalisation complète (visuels par rame, paramètres de flux, commandes additionnelles) est prévue pour une version ultérieure. Les modalités de configuration seront documentées lorsqu’elles seront disponibles.

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
