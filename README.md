# minitrain

Ce projet contient une implémentation complète d'un système de pilotage pour un mini-train connecté, avec un firmware C++ ciblant l'ESP32 (simulé ici) et une application Android (logique Kotlin testable côté JVM).

## Structure

- `firmware/` : code C++17 modulaire (contrôleur PID, gestion des commandes, agrégation de télémétrie) avec une application console de simulation, une implémentation client WebSocket TLS (`wss://`) utilisant mbedTLS et une suite de tests unitaires.
- `android-app/` : module Android (AGP) Kotlin fournissant la pile réseau Ktor avec WebSockets sécurisées, OAuth2 mTLS et tests d'instrumentation.

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
```

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
