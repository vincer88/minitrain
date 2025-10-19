# Spécification de l'interface temps réel

## Vue d'ensemble du protocole

Les exigences sont exprimées de manière normative et regroupées par domaine pour faciliter la vérification et la traçabilité.

### Exigences protocolaires

| ID | Exigence | Vérification |
| --- | --- | --- |
| IF-PROT-01 | Le flux binaire doit transporter des trames fixes de 64 octets encodées en *little-endian* sur l'ensemble des champs multi-octets. | Capture PCAP validée via l'outil `decode_rt_frame` de la suite d'intégration backend. |
| IF-PROT-02 | Chaque trame doit inclure l'en-tête `{session_id, seq, type, flags, timestamp_us, payload_len, reserved}` exactement aux offsets définis, et la longueur utile doit rester ≤ 48 octets. | Décodage structurel par le test `INT-RT-HEADER` (backend ↔ firmware). |
| IF-PROT-03 | Le champ `session_id` doit rester constant pendant toute la durée d'une session TLS et être unique par session active. | Contrôle des journaux de négociation dans le test `SEC-TLS-SESSION`. |
| IF-PROT-04 | Le champ `seq` doit s'incrémenter de 1 par trame, avec wrap 32 bits, et les clients doivent rejeter toute régression. | Vérification des séquences dans `INT-RT-SEQ-CHECK` (firmware ↔ Android). |
| IF-PROT-05 | Les trames `type = 0x0001` doivent transporter les champs de commande (`target_speed_mm_s`, `target_heading_deg`, `lights_pattern`, `safety_margin_mm`, `crc32`) suivis d'un remplissage `0x00`. | Test de conformité `FW-CMD-FORMAT`. |
| IF-PROT-06 | Les trames `type = 0x0002` doivent transporter les champs de télémétrie (`battery_mv`, `imu_yaw_rate_mdps`, `wheel_ticks`, `temperature_mc`, `fail_safe_reason`, `crc32`) suivis d'un remplissage `0x00`. | Test de conformité `FW-TLM-FORMAT`. |
| IF-PROT-07 | Les trames `type = 0x0003` doivent transporter `uptime_ms`, `resync_hint_seq` et un remplissage `0x00`, et doivent être émises à chaque changement de cadence. | Test `INT-RT-KEEPALIVE` (backend ↔ firmware). |

### Exigences de cadence

| ID | Exigence | Vérification |
| --- | --- | --- |
| IF-CAD-01 | Le flux doit opérer à 50 Hz lorsque la latence RTT est &lt; 40 ms et que les pertes cumulées sont &lt; 0,5 %. | Test réseau `NET-CAD-50HZ` (profil nominal). |
| IF-CAD-02 | Le flux doit passer à 25 Hz lorsque les pertes cumulées atteignent ≥ 2 % sur 5 s ou lorsque la charge CPU dépasse 80 %. | Test réseau `NET-CAD-25HZ` (profils pertes/cpu). |
| IF-CAD-03 | Le flux doit se dégrader à 10 Hz lorsque les pertes atteignent ≥ 5 % sur 2 s ou qu'aucun ACK n'est reçu sur 3 trames successives. | Test réseau `NET-CAD-10HZ` (profils pertes/ACK). |
| IF-CAD-04 | Le système doit resynchroniser la session en renvoyant une trame de réinitialisation de séquence et en forçant `fail_safe = 1` lorsque les pertes cumulées dépassent 5 % sur 2 s. | Test `NET-RESYNC-FAILSAFE`. |
| IF-CAD-05 | Chaque transition de cadence doit émettre une trame `keep-alive` avec `resync_hint_seq = seq` pour informer les consommateurs de télémétrie. | Test `INT-RT-KEEPALIVE`. |

### Exigences de sécurité

| ID | Exigence | Vérification |
| --- | --- | --- |
| IF-SEC-01 | La session réseau doit être établie en TLS 1.3 avec des suites AEAD (≥ TLS_AES_128_GCM_SHA256). | Test de négociation `SEC-TLS-VERSIONS`. |
| IF-SEC-02 | L'authentification mutuelle doit s'appuyer sur des certificats X.509 signés par l'autorité interne. | Test `SEC-MTLS-CHAIN`. |
| IF-SEC-03 | Les certificats doivent être renouvelés automatiquement et rotés au plus tard 15 jours avant expiration. | Vérification planifiée dans `SEC-CERT-ROTATION`. |
| IF-SEC-04 | Le serveur doit rejeter tout `session_id` entrant en collision avec une session active. | Test `SEC-SESSION-COLLISION`. |
| IF-SEC-05 | Chaque tentative de négociation échouée doit être consignée dans les journaux d'audit. | Inspection par le test `SEC-AUDIT-LOG`. |

### Exigences de dépendance et de télémétrie

| ID | Exigence | Vérification |
| --- | --- | --- |
| IF-DEP-01 | L'application Android doit corréler flux vidéo et commandes en utilisant `session_id`. | Test d'intégration `MOB-VID-CMD-SYNC` (Android ↔ backend). |
| IF-DEP-02 | L'application Android doit afficher l'alerte UI « Contrôle manuel des feux » lorsque `lights_override = 1`. | Test UI `MOB-LIGHTS-ALERT`. |
| IF-DEP-03 | L'application Android doit afficher la cause de `fail_safe_reason` sur l'écran d'arrêt d'urgence. | Test `MOB-FAILSAFE-UX`. |
| IF-DEP-04 | Le firmware embarqué doit forcer la cadence à 10 Hz et maintenir `fail_safe = 1` tant que l'événement n'est pas désarmé. | Test `FW-FAILSAFE-DECEL`. |
| IF-DEP-05 | Le firmware embarqué doit appliquer `lights_pattern` lorsque `lights_override = 1` et revenir à la logique automatique sinon. | Test `FW-LIGHTS-CTRL`. |
| IF-DEP-06 | Le firmware embarqué doit conserver un compteur interne aligné sur `seq` et déclencher une resynchronisation après wrap 32 bits. | Test `FW-SEQ-DIAG`. |

## Format binaire détaillé

Le format des trames reste inchangé et est rappelé ci-dessous à titre de référence pour les tests décrits dans les exigences.

| Offset (octets) | Taille | Champ | Description |
| --- | --- | --- | --- |
| 0 | 4 | `session_id` | Identifiant de session aléatoire généré à l'ouverture TLS |
| 4 | 4 | `seq` | Numéro de séquence incrémental (wrap sur 32 bits) |
| 8 | 2 | `type` | `0x0001` commande, `0x0002` télémétrie, `0x0003` keep-alive |
| 10 | 2 | `flags` | Bit 0 : `fail_safe`, Bit 1 : `lights_override`, Bit 2 : `ack_required` |
| 12 | 4 | `timestamp_us` | Horodatage en microsecondes depuis l'époque Unix |
| 16 | 2 | `payload_len` | Longueur utile (doit être ≤ 48) |
| 18 | 2 | `reserved` | Alignement (doit être 0) |
| 20 | 44 | `payload` | Champ spécifique au type |

## Exemples de trames

### Exemple de commande (hexadécimal)

```
01 00 00 00  2A 00 00 00  01 00  02 00  50 4B 02 00  14 00  00 00
E8 03 00 00  00 00 34 42  03 00 00 00  20 03 00 00
5D 7A A6 1C  00 00 00 00  ... (padding 24 octets)
```

- `session_id = 0x00000001`
- `seq = 42`
- `type = commande`
- `flags = fail_safe=0, lights_override=1`
- `timestamp_us = 150000`
- `payload` : vitesse 1000 mm/s, cap 45°, motif lumineux 3, marge de sécurité 800 mm.

### Exemple de télémétrie (hexadécimal)

```
02 00 00 00  2B 00 00 00  02 00  01 00  60 4B 02 00  14 00  00 00
20 0F 00 00  10 27 00 00  58 02 00 00  B0 0B 00 00
01 00 00 00  4F C6 3B 12  ... (padding 20 octets)
```

- `flags = fail_safe=1` déclenchant l'alerte backend et Android.
- `fail_safe_reason = 1` (obstruction capteur), transmis à l'app mobile.

### Séquence d'échange typique

1. Établissement TLS 1.3 avec authentification mutuelle et génération du `session_id`.
2. Envoi périodique de commandes à 50 Hz ; accusés de réception implicites via `seq`.
3. Passage en mode dégradé (pertes &gt; 5 %) : envoi d'un `keep-alive` avec `resync_hint_seq` puis réduction à 10 Hz.
4. Télémétrie continue à la nouvelle cadence, avec `fail_safe` positionné si la sécurité l'exige.
5. Rétablissement : lorsque les pertes reviennent &lt; 1 % sur 10 s, retour à 25 Hz puis 50 Hz et émission d'un nouveau `keep-alive` pour signaler la reprise.

Ces exemples servent de base pour les tests d'intégration : les suites automatisées doivent vérifier la conformité du format et la synchronisation `session_id`/`seq` entre l'app Android, le backend et le firmware.

## Traçabilité

| ID | Tests réseau associés | Commentaires |
| --- | --- | --- |
| IF-PROT-01 à IF-PROT-07 | `INT-RT-HEADER`, `FW-CMD-FORMAT`, `FW-TLM-FORMAT`, `INT-RT-KEEPALIVE` | Couverture complète visée par les suites backend ↔ firmware, capture PCAP à compléter pour `IF-PROT-01`. |
| IF-CAD-01 à IF-CAD-05 | `NET-CAD-50HZ`, `NET-CAD-25HZ`, `NET-CAD-10HZ`, `NET-RESYNC-FAILSAFE` | Profils réseau simulés dans le banc de test `NET-SHAPER`. |
| IF-SEC-01 à IF-SEC-05 | `SEC-TLS-VERSIONS`, `SEC-MTLS-CHAIN`, `SEC-CERT-ROTATION`, `SEC-SESSION-COLLISION`, `SEC-AUDIT-LOG` | Les tests `SEC-CERT-ROTATION` et `SEC-AUDIT-LOG` sont à planifier sur l'environnement de préproduction. |
| IF-DEP-01 à IF-DEP-06 | `MOB-VID-CMD-SYNC`, `MOB-LIGHTS-ALERT`, `MOB-FAILSAFE-UX`, `FW-FAILSAFE-DECEL`, `FW-LIGHTS-CTRL`, `FW-SEQ-DIAG` | Couverture croisée backend ↔ firmware ↔ Android ; coordination avec les sprints AN et FW. |

## Synchronisation inter-spécifications

| Domaine | Référence croisée | Description |
| --- | --- | --- |
| Android | `AN-RF-07`, `AN-RF-12` | Harmoniser les alertes UI (`lights_override`, `fail_safe_reason`) avec les scénarios décrits dans les spécifications radio Android. |
| Firmware | `FW-F-03`, `FW-F-09`, `FW-F-14` | Synchroniser la logique de cadence, la gestion `fail_safe` et la resynchronisation de séquence avec les exigences firmware. |
| Backend analytique | `AN-RF-21` | Vérifier que les pipelines de télémétrie consomment les champs `timestamp_us` et `resync_hint_seq` conformément au bus interne. |
