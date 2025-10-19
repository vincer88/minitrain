# Spécification du contrôleur ESP32

## 1. Vue d'ensemble
Le contrôleur embarqué sur ESP32 assure la supervision de l'éclairage des trains miniatures :

- il agrège les commandes provenant du backend (canal MQTT ou équivalent) ;
- il arbitre l'état d'éclairage courant en tenant compte des rampes de transition et des overrides de sécurité ;
- il publie une télémétrie consolidée destinée au monitoring et aux tests automatiques ;
- il garantit qu'en absence de commande valide, l'éclairage reste dans un état sûr grâce à des rampes fail-safe.

## 2. Exigences fonctionnelles
### 2.1 Gestion de session
- Le contrôleur doit maintenir une session active avec la passerelle backend en appliquant les temporisations `T₁` (revalidation périodique) et `T₂` (expiration). La session est considérée valide si un message KeepAlive ou une commande valide est reçue avant `T₂` après la dernière activité.
- En cas d'expiration (`T₂` dépassé), le contrôleur bascule vers l'état **Fail-Safe** et émet un événement de session expirée dans la télémétrie.
- En présence de plusieurs contrôleurs, chaque instance doit utiliser un identifiant de session unique (`session_id`) fourni lors de l'initialisation.
- Les tests d'intégration à étendre dans `firmware/tests/test_command_channel.cpp` doivent vérifier que l'expiration de session déclenche le fallback attendu et que la réception d'une commande valide réactive la session.

### 2.2 Rampes fail-safe
- Le contrôleur gère deux rampes principales : montée et descente. Les durées configurables `ramp_up_duration` et `ramp_down_duration` doivent être respectées à ±5 % pour éviter les variations brusques.
- Lorsqu'une session expire ou qu'une perte de commandes est détectée (absence de messages pendant `T₁`), le contrôleur déclenche la rampe de descente vers l'état **Sécurité**.
- À la reprise des commandes, la rampe de montée est utilisée pour revenir à l'état cible.
- Les tests unitaires dans `firmware/tests/test_train_controller.cpp` couvrent la séquence de rampes nominales ; ajouter des cas pour la précision temporelle et le déclenchement fail-safe.

### 2.3 Critères de disponibilité
- Le contrôleur doit annoncer l'état **Disponible** lorsque : session valide, alimentation stable, pas d'override manuel actif.
- Un état **Dégradé** est annoncé si la session est valide mais qu'un capteur critique remonte une anomalie (capteur de courant, température). L'état passe à **Indisponible** si la session est expirée ou si l'alimentation chute sous le seuil défini.
- Ces états doivent être exposés via la télémétrie et par la LED de statut (pattern configurable).
- Couvrir ces transitions via des tests fonctionnels à créer dans `firmware/tests/test_main.cpp`.

### 2.4 États d'éclairage
- Le contrôleur implémente les états suivants : **Off**, **Idle**, **Run**, **Override**, **Fail-Safe**.
- Les transitions valides sont :
  - **Off** → **Idle** lors de l'initialisation réussie ;
  - **Idle** ↔ **Run** via commandes de la passerelle ;
  - Tout état → **Override** lorsqu'un opérateur local force un mode manuel ;
  - Tout état → **Fail-Safe** sur expiration de session ou défaut critique ;
  - **Fail-Safe** → **Idle** uniquement après rétablissement de la session et accquittement explicite.
- Les intensités lumineuses cibles sont définies pour chaque état et doivent respecter les rampes configurées.
- Valider les transitions via `firmware/tests/test_command_processor.cpp` et compléter par des scénarios d'override manuel.

### 2.5 Télémétrie publiée
- Le contrôleur publie périodiquement (intervalle `telemetry_period`) les champs suivants :
  - `session_id`, `session_state` (valeurs : `active`, `expired`, `restoring`)
  - `availability_state` (valeurs : `available`, `degraded`, `unavailable`)
  - `lighting_state` (valeurs : `off`, `idle`, `run`, `override`, `fail_safe`)
  - `last_command_timestamp`
  - `ramp_progress` (0.0–1.0)
  - `supply_voltage_v`
  - `board_temperature_c`
  - `error_flags` (bitmask)
- À chaque événement critique (expiration, override, récupération), une télémétrie immédiate (« burst ») est émise sans attendre `telemetry_period`.
- La cohérence des messages est vérifiée dans `firmware/tests/test_telemetry.cpp` ; ajouter des assertions sur les nouveaux champs et la fréquence de publication.

## 3. Exigences non fonctionnelles
- **Fiabilité :** Le contrôleur doit survivre à des cycles de redémarrage de l'ESP32 sans corrompre l'état de session persistant ; inclure un watchdog interne avec timeout `< T₂`.
- **Performance :** Le temps de traitement d'une commande ne doit pas excéder 10 ms en moyenne. Les rampes doivent être échantillonnées à une fréquence minimale de 50 Hz pour éviter le flicker perceptible.
- **Sécurité :** Les overrides manuels doivent être journalisés localement (SPIFFS) et reflétés dans la télémétrie. Toute commande distante doit être authentifiée avant application.
- **Observabilité :** Les logs internes utilisent des niveaux `DEBUG/INFO/WARN/ERROR`. En mode production, seules les erreurs et événements critiques sont remontés pour limiter l'usage réseau.
- **Configurabilité :** Toutes les constantes (`T₁`, `T₂`, durées de rampe, `telemetry_period`, seuils d'alimentation) doivent être centralisées dans un module de configuration (`config_controller.hpp`) et modifiables via OTA.

## 4. Paramètres configurables
| Paramètre | Description | Valeur par défaut | Source |
|-----------|-------------|-------------------|--------|
| `T₁` | Intervalle maximal entre deux commandes avant avertissement de perte | 5 s | Configuration OTA |
| `T₂` | Timeout d'expiration de session | 15 s | Configuration OTA |
| `ramp_up_duration` | Durée de montée vers l'intensité cible | 2 s | Module d'éclairage |
| `ramp_down_duration` | Durée de descente vers l'état sûr | 1.5 s | Module d'éclairage |
| `telemetry_period` | Intervalle de publication périodique | 2 s | Stack MQTT |
| `voltage_min_threshold` | Seuil basse tension | 4.5 V | Capteur d'alimentation |
| `override_timeout` | Durée max d'un override manuel avant retour Idle | 60 s | Interface opérateur |

## 5. Gestion des scénarios d'erreur
- **Perte de commandes (`T₁` dépassé, `T₂` non atteint)** : déclenche un avertissement, passage en état **Dégradé**, rampe descendante partielle (50 % de l'intensité) et publication d'un message de télémétrie immédiat. Si les commandes reprennent, retour à l'état initial via rampe montante.
- **Expiration de session (`T₂` dépassé)** : arrêt progressif de l'éclairage via rampe descendante complète, passage en état **Fail-Safe**, verrouillage des commandes distantes jusqu'à réception d'un nouveau token de session. La télémétrie doit inclure un `error_flag` `SESSION_EXPIRED`.
- **Override d'éclairage** : lorsqu'un override local est activé, le contrôleur passe en état **Override**, suspend les commandes distantes, et démarre un timer `override_timeout`. À expiration, retour vers **Idle** avec rampe descendante. Si un override distant invalide est détecté, ignorer la commande et publier un `error_flag` `OVERRIDE_CONFLICT`.
- **Défaut matériel (tension basse ou surchauffe)** : passage immédiat en **Fail-Safe**, désactivation des rampes pour garantir un arrêt rapide, et génération d'un événement critique. Requiert un acquittement manuel avant reprise.

Les scénarios ci-dessus doivent être couverts par :
- des tests automatisés existants (`firmware/tests/test_train_controller.cpp`, `firmware/tests/test_command_processor.cpp`, `firmware/tests/test_telemetry.cpp`) enrichis pour inclure les nouveaux cas d'erreur ;
- des tests supplémentaires à planifier dans `firmware/tests/test_command_channel.cpp` pour simuler l'absence de commandes et la restauration de session ;
- des tests d'intégration hardware-in-the-loop à documenter dans un futur plan de validation (`docs/tests/hil-session-loss.md`, à créer).

## 6. Traçabilité et validation
- Chaque exigence fonctionnelle doit être liée à un ou plusieurs tests automatisés mentionnés ci-dessus. Mettre à jour le plan de test (`firmware/tests/test_suite.hpp`) pour inclure les nouveaux cas.
- Les métriques de couverture (cible : ≥ 85 % sur les modules `session_manager`, `lighting_controller`, `telemetry_publisher`) sont collectées via `ctest` avec rapport GCOV ; ajouter un ticket pour automatiser ce contrôle dans la CI.
- Les audits de configuration doivent vérifier que `T₁`, `T₂` et les durées de rampe concordent entre les firmwares déployés et la documentation OTA.

