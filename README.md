# minitrain

Ce projet contient une implémentation complète d'un système de pilotage pour un mini-train connecté, avec un firmware C++ ciblant l'ESP32 (simulé ici) et une application Android (logique Kotlin testable côté JVM).

## Structure

- `firmware/` : code C++17 modulaire (contrôleur PID, gestion des commandes, agrégation de télémétrie) avec une application console de simulation, une implémentation client WebSocket TLS (`wss://`) utilisant mbedTLS et une suite de tests unitaires.
- `android-app/` : module Android (AGP) Kotlin fournissant la pile réseau Ktor avec WebSockets sécurisées, OAuth2 mTLS et tests d'instrumentation.

## Vue opérateur

La vue opérateur se compose de deux écrans complémentaires :

1. **Sélection d'un train** – liste verticale minimaliste de rames disponibles. Chaque entrée affiche l'alias, l'état courant et expose deux boutons texte (*Activer* et *Détails*) pour engager la cabine ou consulter la télémétrie. Les états se mettent à jour en temps réel via la télémétrie agrégée.
2. **Cabine immersive** – après sélection, une surimpression plein écran affiche :
   - Le flux vidéo cabine (ESP32) en arrière-plan.
   - Un manipulateur virtuel (curseur circulaire) permettant de moduler vitesse et sens via gestes.
   - Des sélecteurs contextuels (feux, relâchement, profil de vitesse) accessibles en surcouche.
   - Un bandeau indiquant la connexion et une action *Retour* vers la liste si la disponibilité est perdue.

## Prérequis

- CMake ≥ 3.16 et un compilateur C++17 (g++, clang++…)
- Java 17+ et Gradle (Wrapper fourni via Gradle installé sur la machine)

## Lancer les tests

### Firmware C++

```bash
cmake -S firmware -B firmware/build
cmake --build firmware/build
ctest --test-dir firmware/build
```

### Application Android

```bash
cd android-app
gradle test
gradle connectedAndroidTest # instrumentation TLS/mTLS
gradle :app:connectedComposeDebugAndroidTest # tests UI Compose de la vue cabine
```

> **Prérequis UI** : fournir un endpoint vidéo de test (ESP32 ou mock HLS/RTSP) accessible localement et référencé dans `local.properties` (`cab.video.previewUrl=`) pour que les tests de la vue cabine valident les états « flux disponible/perdu ».

Les nouveaux tests Compose instrumentés (`TrainControlScreenTest`) pilotent la glissière de vitesse, les boutons de direction et la sélection de cabine, tout en vérifiant les interactions avec `TrainViewModel`. Ils capturent également les overlays vidéo (buffering/erreur) et génèrent des captures d'écran enregistrées sur l'appareil sous `Android/data/com.minitrain/files/reports/screenshots`. Après exécution, rapatriez-les localement dans `android-app/app/build/reports/screenshots/` avec :

```bash
adb pull /sdcard/Android/data/com.minitrain/files/reports/screenshots android-app/app/build/reports/screenshots/
```

## Configuration des visuels cabine et flux vidéo

Les visuels de cabine sont stockés dans `android-app/app/src/main/res/drawable/cab/` et `android-app/app/src/main/res/raw/overlays/`. Pour chaque rame, associez un identifiant de ressource dans `android-app/app/src/main/assets/cabs.json` :

```json
[
  {
    "trainId": "urn:train:alpha",
    "overlay": "cab_alpha.png",
    "manipulator": "manipulator_default.json"
  }
]
```

L'application charge dynamiquement l'overlay selon `trainId`. Les overlays manquants retombent sur `manipulator_default`. Pour ajouter un visuel :

1. Déposez le fichier graphique dans `res/drawable/cab/` (PNG 9-patch recommandé).
2. Référencez-le dans `cabs.json` avec le même `trainId` que celui du backend.
3. Redémarrez l'application ou déclenchez un *hot reload* Compose pour l'appliquer.

Le flux vidéo cabine provient de l'ESP32 diffusant en MJPEG ou RTSP. Configurez l'URL par rame dans `cabs.json` via la clé `videoUrl`. En environnement de développement, un simulateur (`scripts/mock_video_server.py`) peut générer un flux HLS de test :

```bash
python scripts/mock_video_server.py --source assets/sample.mp4 --port 8088
```

Renseignez ensuite `videoUrl` vers `http://127.0.0.1:8088/stream.mjpeg`. En production, l'ESP32 doit exposer un endpoint TLS et authentifié.

Les tests valident l'ensemble des comportements critiques : PID, agrégateur de télémétrie, traitement des commandes, logique de ViewModel et interactions réseau simulées. Les tests d'instrumentation démarrent un serveur TLS auto-signé pour vérifier l'établissement d'une session WebSocket mTLS.

## Provisionnement des secrets et rotation

La configuration des certificats (CA, client et clé privée) ainsi que des paramètres OAuth2 est documentée dans [`docs/security-provisioning.md`](docs/security-provisioning.md). Les paramètres Gradle et les manifestes décrivent comment injecter ces secrets via vos pipelines de provisionnement.

## Exécution via Docker

Un `Dockerfile` est fourni pour créer une image capable de construire et d'exécuter tous les tests sans installer les dépendances sur la machine hôte.

### Construire l'image

```bash
docker build -t minitrain:latest .
```

### Lancer les tests dans un conteneur éphémère

```bash
docker run --rm minitrain:latest
```

### Utiliser docker-compose pour lancer la chaîne complète

```bash
docker compose up --build
```

La commande `docker compose` construit l'image à partir du `Dockerfile`, monte le code local et exécute la même séquence de tests que ci-dessus.
