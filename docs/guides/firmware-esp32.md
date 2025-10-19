# Guide développeur – Firmware ESP32

Ce guide décrit les opérations de construction, de test et de déploiement du firmware ESP32. Les comportements attendus, états et exigences fonctionnelles demeurent centralisés dans la [spécification du contrôleur ESP32](../specs/firmware-esp32.md).

## Construire le firmware

1. Configurer la build :
   ```bash
   cmake -S firmware -B firmware/build \
         -DCMAKE_BUILD_TYPE=Release
   ```
2. Compiler :
   ```bash
   cmake --build firmware/build
   ```
   Les binaires générés se trouvent dans `firmware/build/`.

## Lancer les tests

- Tests unitaires :
  ```bash
  ctest --test-dir firmware/build
  ```
- Scénarios simulés : exécutez les binaires d'exemple produits (par ex. `./firmware/build/simulateur`) pour rejouer des scénarios de rampes et de fail-safe.

Les cas de test doivent couvrir les transitions et rampes décrites par la spécification (gestion des sessions, télémétrie, overrides).

## Déployer sur un ESP32

1. Installer le SDK [ESP-IDF](https://docs.espressif.com/).
2. Sélectionner la cible : `idf.py set-target esp32`.
3. Ajuster la configuration : `idf.py -C firmware menuconfig`.
4. Construire et flasher :
   ```bash
   idf.py -C firmware build
   idf.py -C firmware flash
   idf.py -C firmware monitor
   ```

Le projet vérifie que la cible `ESP32` est sélectionnée pour éviter le flash sur un SoC incompatible. La migration vers le canal temps réel impose également de respecter les rampes et états décrits dans la spécification.
