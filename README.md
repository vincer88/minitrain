# minitrain

Ce projet contient une implémentation complète d'un système de pilotage pour un mini-train connecté, avec un firmware C++ ciblant l'ESP32 (simulé ici) et une application Android (logique Kotlin testable côté JVM).

## Structure

- `firmware/` : code C++17 modulaire (contrôleur PID, gestion des commandes, agrégation de télémétrie) avec une application console de simulation et une suite de tests unitaires.
- `android-app/` : logique applicative Kotlin (encodage des commandes, dépôt réseau basé sur Ktor, ViewModel coroutines) et tests unitaires JVM.

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

### Application Kotlin

```bash
cd android-app
gradle test
```

Les tests valident l'ensemble des comportements critiques : PID, agrégateur de télémétrie, traitement des commandes, logique de ViewModel et interactions réseau simulées.
