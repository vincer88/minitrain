# Spécification du contrôleur ESP32

## 1. Vue d'ensemble

| ID | Exigence | Vérification |
|----|----------|--------------|
| FW-F-01 | Le contrôleur doit agréger les commandes provenant du backend via le canal MQTT (ou équivalent) afin d'assurer une supervision centralisée de l'éclairage. | `firmware/tests/test_command_channel.cpp` (à renforcer pour les flux agrégés). |
| FW-F-02 | Le contrôleur doit arbitrer l'état d'éclairage courant en tenant compte des rampes de transition et des overrides de sécurité pour maintenir une luminosité cohérente. | `firmware/tests/test_train_controller.cpp` (séquences de rampes) ; nouveau scénario d'override manuel. |
| FW-F-03 | Le contrôleur doit publier une télémétrie consolidée destinée au monitoring et aux tests automatiques afin de refléter son état interne. | `firmware/tests/test_telemetry.cpp` (à compléter pour la télémétrie consolidée). |
| FW-F-04 | Le contrôleur doit garantir qu'en absence de commande valide l'éclairage reste dans un état sûr en appliquant les rampes fail-safe configurées. | `firmware/tests/test_command_channel.cpp` (expiration de commandes) ; `firmware/tests/test_train_controller.cpp` (rampe fail-safe). |

## 2. Exigences fonctionnelles

### 2.1 Gestion de session

| ID | Exigence | Vérification |
|----|----------|--------------|
| FW-F-05 | Le contrôleur doit maintenir une session active avec la passerelle backend en appliquant les temporisations `T₁` et `T₂`, et il doit considérer la session valide lorsqu'un message KeepAlive ou une commande valide arrive avant l'expiration `T₂`. | `firmware/tests/test_command_channel.cpp` (à étendre pour la fenêtre `T₁`/`T₂`). |
| FW-F-06 | Le contrôleur doit basculer vers l'état **Fail-Safe** et émettre un événement de session expirée dans la télémétrie lorsque `T₂` est dépassé sans activité. | `firmware/tests/test_command_channel.cpp` (fallback attendu) ; `firmware/tests/test_telemetry.cpp` (événement d'expiration). |
| FW-F-07 | Chaque instance de contrôleur doit utiliser un identifiant de session unique (`session_id`) fourni lors de l'initialisation afin d'éviter les collisions multi-contrôleurs. | Nouveau test d'initialisation multi-instances dans `firmware/tests/test_command_channel.cpp`. |

### 2.2 Rampes fail-safe

| ID | Exigence | Vérification |
|----|----------|--------------|
| FW-F-08 | Le contrôleur doit respecter les durées configurables `ramp_up_duration` et `ramp_down_duration` à ±5 % pour éviter toute variation lumineuse brusque. | `firmware/tests/test_train_controller.cpp` (ajouter mesure de précision temporelle). |
| FW-F-09 | Le contrôleur doit déclencher la rampe de descente vers l'état **Sécurité** lorsqu'une session expire ou qu'une perte de commandes (`T₁` dépassé) est détectée. | `firmware/tests/test_command_channel.cpp` (perte de commandes) ; `firmware/tests/test_train_controller.cpp` (descente fail-safe). |
| FW-F-10 | Le contrôleur doit utiliser la rampe de montée pour revenir à l'état cible dès que les commandes reprennent après un incident. | `firmware/tests/test_train_controller.cpp` (retour via rampe montante). |

### 2.3 Critères de disponibilité

| ID | Exigence | Vérification |
|----|----------|--------------|
| FW-F-11 | Le contrôleur doit annoncer l'état **Disponible** lorsque la session est valide, l'alimentation est stable et aucun override manuel n'est actif. | Nouveau test fonctionnel dans `firmware/tests/test_main.cpp`. |
| FW-F-12 | Le contrôleur doit annoncer l'état **Dégradé** si la session reste valide mais qu'un capteur critique signale une anomalie, et il doit annoncer l'état **Indisponible** si la session est expirée ou si l'alimentation passe sous le seuil. | `firmware/tests/test_main.cpp` (à créer pour les transitions dégradées et indisponibles). |
| FW-F-13 | Le contrôleur doit exposer les états de disponibilité via la télémétrie et via un pattern de LED de statut configurable. | `firmware/tests/test_telemetry.cpp` (états de disponibilité) ; nouveau test LED dans `firmware/tests/test_main.cpp`. |

### 2.4 États d'éclairage

| ID | Exigence | Vérification |
|----|----------|--------------|
| FW-F-14 | Le contrôleur doit implémenter les états d'éclairage **Off**, **Idle**, **Run**, **Override** et **Fail-Safe** conformément au modèle d'état défini. | `firmware/tests/test_command_processor.cpp` (couverture des états). |
| FW-F-15 | Le contrôleur doit limiter les transitions d'état aux chemins valides définis (Off→Idle, Idle↔Run, tout état→Override, tout état→Fail-Safe, Fail-Safe→Idle après rétablissement et acquittement). | `firmware/tests/test_command_processor.cpp` (transitions valides) ; nouveau scénario d'override manuel. |
| FW-F-16 | Le contrôleur doit appliquer pour chaque état l'intensité lumineuse cible en respectant les rampes configurées. | `firmware/tests/test_train_controller.cpp` (intensités et rampes). |

### 2.5 Télémétrie publiée

| ID | Exigence | Vérification |
|----|----------|--------------|
| FW-F-17 | Le contrôleur doit publier à l'intervalle `telemetry_period` une télémétrie contenant `session_id`, `session_state`, `availability_state`, `lighting_state`, `last_command_timestamp`, `ramp_progress`, `supply_voltage_v`, `board_temperature_c` et `error_flags`. | `firmware/tests/test_telemetry.cpp` (ajouter assertions sur les champs). |
| FW-F-18 | Le contrôleur doit émettre immédiatement une télémétrie supplémentaire à chaque événement critique (expiration, override, récupération) sans attendre `telemetry_period`. | `firmware/tests/test_telemetry.cpp` (fréquence d'émission) ; `firmware/tests/test_command_channel.cpp` (événements critiques). |

## 3. Exigences non fonctionnelles

| ID | Exigence | Vérification |
|----|----------|--------------|
| FW-NF-01 | Le contrôleur doit survivre aux cycles de redémarrage ESP32 sans corrompre l'état de session persistant et doit inclure un watchdog interne dont le timeout est strictement inférieur à `T₂`. | Nouveau test de redémarrage simulé dans `firmware/tests/test_main.cpp`. |
| FW-NF-02 | Le contrôleur doit traiter chaque commande en moins de 10 ms en moyenne et échantillonner les rampes à une fréquence minimale de 50 Hz pour éviter le flicker perceptible. | Benchmarks automatisés dans `firmware/tests/test_train_controller.cpp` (nouveaux timers). |
| FW-NF-03 | Le contrôleur doit journaliser localement sur SPIFFS chaque override manuel et authentifier toute commande distante avant application. | `firmware/tests/test_command_processor.cpp` (à compléter pour l'authentification) ; nouveau test SPIFFS simulé. |
| FW-NF-04 | Le contrôleur doit limiter les logs réseau aux événements critiques en production tout en conservant les niveaux `DEBUG/INFO/WARN/ERROR` en développement. | Audit de configuration dans `firmware/tests/test_suite.hpp` (à compléter) ; revue manuelle CI. |
| FW-NF-05 | Le contrôleur doit centraliser les constantes `T₁`, `T₂`, `ramp_up_duration`, `ramp_down_duration`, `telemetry_period` et les seuils d'alimentation dans `config_controller.hpp` et permettre leur mise à jour OTA. | `firmware/tests/test_main.cpp` (chargement config) ; inspection OTA automatisée (à planifier). |

## 4. Paramètres configurables

| Paramètre | Description | Valeur par défaut | Source | Exigences associées |
|-----------|-------------|-------------------|--------|---------------------|
| `T₁` | Intervalle maximal entre deux commandes avant avertissement de perte | 5 s | Configuration OTA | FW-F-05, FW-F-09 |
| `T₂` | Timeout d'expiration de session | 15 s | Configuration OTA | FW-F-05, FW-F-06, FW-NF-01 |
| `ramp_up_duration` | Durée de montée vers l'intensité cible | 2 s | Module d'éclairage | FW-F-08, FW-F-10, FW-F-16 |
| `ramp_down_duration` | Durée de descente vers l'état sûr | 1.5 s | Module d'éclairage | FW-F-08, FW-F-09, FW-F-16, FW-ERR-01 |
| `telemetry_period` | Intervalle de publication périodique | 2 s | Stack MQTT | FW-F-17 |
| `voltage_min_threshold` | Seuil basse tension | 4.5 V | Capteur d'alimentation | FW-F-12, FW-ERR-04 |
| `override_timeout` | Durée max d'un override manuel avant retour Idle | 60 s | Interface opérateur | FW-F-15, FW-ERR-03 |

## 5. Gestion des scénarios d'erreur

| ID | Exigence | Vérification |
|----|----------|--------------|
| FW-ERR-01 | Le contrôleur doit, en cas de perte de commandes (`T₁` dépassé sans atteindre `T₂`), déclencher un avertissement, passer en état **Dégradé**, appliquer une rampe descendante partielle à 50 % de l'intensité et publier immédiatement une télémétrie ; il doit revenir à l'état initial via rampe montante si les commandes reprennent. | `firmware/tests/test_command_channel.cpp` (simulation perte/reprise) ; `firmware/tests/test_train_controller.cpp` (rampe partielle). |
| FW-ERR-02 | Le contrôleur doit, en cas d'expiration de session (`T₂` dépassé), arrêter l'éclairage via une rampe descendante complète, passer en état **Fail-Safe**, verrouiller les commandes distantes jusqu'à réception d'un nouveau token et publier l'`error_flag` `SESSION_EXPIRED`. | `firmware/tests/test_command_channel.cpp` (expiration) ; `firmware/tests/test_telemetry.cpp` (flag SESSION_EXPIRED). |
| FW-ERR-03 | Le contrôleur doit, lors d'un override local, passer en état **Override**, suspendre les commandes distantes, démarrer le timer `override_timeout` et revenir en **Idle** par rampe descendante à l'expiration tout en signalant `OVERRIDE_CONFLICT` pour tout override distant invalide. | `firmware/tests/test_command_processor.cpp` (override local/distant) ; nouveau test `override_timeout`. |
| FW-ERR-04 | Le contrôleur doit, lors d'un défaut matériel (tension basse ou surchauffe), passer immédiatement en **Fail-Safe**, désactiver les rampes pour arrêter rapidement l'éclairage, générer un événement critique et exiger un acquittement manuel avant reprise. | `firmware/tests/test_main.cpp` (défaut matériel) ; test HIL `docs/tests/hil-session-loss.md` (à créer). |

## 6. Traçabilité et validation

| ID | Exigence | Vérification |
|----|----------|--------------|
| FW-NF-06 | Chaque exigence fonctionnelle doit être liée à au moins un test automatisé référencé dans la table de traçabilité et le plan de test `firmware/tests/test_suite.hpp` doit être mis à jour en conséquence. | Revue du plan de test dans `firmware/tests/test_suite.hpp`. |
| FW-NF-07 | Les métriques de couverture doivent atteindre ≥ 85 % sur `session_manager`, `lighting_controller` et `telemetry_publisher` et être collectées via `ctest` avec rapport GCOV intégré à la CI. | Rapport `ctest`/GCOV (pipeline CI). |
| FW-NF-08 | Les audits de configuration doivent vérifier la cohérence de `T₁`, `T₂` et des durées de rampe entre les firmwares déployés et la documentation OTA. | Audit manuel (runbook) ; script à développer dans `scripts/audit-config`. |

## 7. Tableau de traçabilité des exigences et des tests

| ID | Tests existants (`firmware/tests/...`) | Tests à créer ou à compléter |
|----|----------------------------------------|--------------------------------|
| FW-F-01 | test_command_channel.cpp | Renforcer la couverture des flux agrégés. |
| FW-F-02 | test_train_controller.cpp | Scénario d'override manuel à ajouter. |
| FW-F-03 | test_telemetry.cpp | Assertions supplémentaires sur les champs consolidés. |
| FW-F-04 | test_command_channel.cpp ; test_train_controller.cpp | — |
| FW-F-05 | test_command_channel.cpp | Cas de validation KeepAlive vs `T₂`. |
| FW-F-06 | test_command_channel.cpp ; test_telemetry.cpp | — |
| FW-F-07 | — | Nouveau test multi-instances dans test_command_channel.cpp. |
| FW-F-08 | test_train_controller.cpp | Mesure de précision ±5 %. |
| FW-F-09 | test_command_channel.cpp ; test_train_controller.cpp | — |
| FW-F-10 | test_train_controller.cpp | — |
| FW-F-11 | — | Nouveau test de disponibilité dans test_main.cpp. |
| FW-F-12 | — | Cas d'anomalie capteur et chute tension dans test_main.cpp. |
| FW-F-13 | test_telemetry.cpp | Test LED dans test_main.cpp. |
| FW-F-14 | test_command_processor.cpp | — |
| FW-F-15 | test_command_processor.cpp | Scénario d'override manuel complémentaire. |
| FW-F-16 | test_train_controller.cpp | — |
| FW-F-17 | test_telemetry.cpp | — |
| FW-F-18 | test_telemetry.cpp ; test_command_channel.cpp | — |
| FW-NF-01 | — | Simulation de redémarrage dans test_main.cpp. |
| FW-NF-02 | — | Benchmarks timers dans test_train_controller.cpp. |
| FW-NF-03 | test_command_processor.cpp | Test SPIFFS/override à ajouter. |
| FW-NF-04 | — | Audit configuration automatisé (CI). |
| FW-NF-05 | — | Test de chargement OTA dans test_main.cpp. |
| FW-NF-06 | test_suite.hpp | — |
| FW-NF-07 | — | Rapport GCOV automatisé dans la CI. |
| FW-NF-08 | — | Script `scripts/audit-config` à développer. |
| FW-ERR-01 | test_command_channel.cpp ; test_train_controller.cpp | — |
| FW-ERR-02 | test_command_channel.cpp ; test_telemetry.cpp | — |
| FW-ERR-03 | test_command_processor.cpp | Nouveau test override_timeout. |
| FW-ERR-04 | — | test_main.cpp (défaut matériel) ; plan HIL `docs/tests/hil-session-loss.md`. |


