# Spécification de l'interface temps réel

## Vue d'ensemble du protocole

L'interface temps réel véhicule un flux binaire bidirectionnel encapsulé dans une session TLS 1.3 avec authentification mutuelle. Les messages sont encodés en *little-endian* et alignés sur des trames fixes décrites ci-dessous. La cadence nominale est de 50 Hz (20 ms par trame), avec des mécanismes de dégradation contrôlée jusqu'à 10 Hz en cas de contraintes réseau ou de surcharge.

Chaque flux logique est corrélé via les champs `session_id` (identifiant stable pour la durée de la session sécurisée) et `seq` (numéro de séquence monotone pour la détection d'ordonnancement et des pertes). Les messages de commande et de télémétrie partagent ces règles de corrélation afin de faciliter l'agrégation côté serveur et la reconstruction côté client.

## Cadence, tolérance aux pertes et dégradation

| Condition réseau ou système | Cadence appliquée | Critère de déclenchement | Comportement complémentaire |
| --- | --- | --- | --- |
| Nominale | 50 Hz | Latence RTT &lt; 40 ms et pertes &lt; 0,5 % | Transmission complète de la télémétrie et des commandes |
| Dégradée | 25 Hz | Pertes cumulées &ge; 2 % sur 5 s ou CPU &gt; 80 % | Réduction de la densité des messages de télémétrie non critiques |
| Critique | 10 Hz | Pertes &ge; 5 % sur 2 s ou absence d'ACK sur 3 trames successives | Suspension des messages optionnels, priorisation des commandes critiques |

La tolérance aux pertes est limitée à 5 % cumulés sur un intervalle mobile de 2 s. Au-delà, la session doit être resynchronisée (renvoi d'un paquet de réinitialisation de séquence) et les indicateurs `fail_safe` doivent être forcés à `1`.

## Format binaire des trames

Chaque trame possède un en-tête commun suivi d'un bloc de données dépendant du type. La longueur totale est fixée à 64 octets.

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

### Payload type commande (`type = 0x0001`)

| Offset relatif | Taille | Champ | Description |
| --- | --- | --- | --- |
| 0 | 4 | `target_speed_mm_s` | Vitesse linéaire cible |
| 4 | 4 | `target_heading_deg` | Cap en degrés |
| 8 | 4 | `lights_pattern` | Identifiant de motif lumineux appliqué si `lights_override = 1` |
| 12 | 4 | `safety_margin_mm` | Distance de sécurité minimale |
| 16 | 4 | `crc32` | CRC32 du bloc `payload` pour vérification |
| 20 | 24 | `padding` | Doit être rempli de `0x00` |

### Payload type télémétrie (`type = 0x0002`)

| Offset relatif | Taille | Champ | Description |
| --- | --- | --- | --- |
| 0 | 4 | `battery_mv` | Tension batterie |
| 4 | 4 | `imu_yaw_rate_mdps` | Vitesse de rotation mesurée |
| 8 | 4 | `wheel_ticks` | Incréments d'encodeur cumulés |
| 12 | 4 | `temperature_mc` | Température moteur en milli-degrés Celsius |
| 16 | 4 | `fail_safe_reason` | Code de cause si `fail_safe = 1` |
| 20 | 4 | `crc32` | CRC32 du bloc `payload` |
| 24 | 20 | `padding` | Doit être rempli de `0x00` |

### Payload type keep-alive (`type = 0x0003`)

| Offset relatif | Taille | Champ | Description |
| --- | --- | --- | --- |
| 0 | 4 | `uptime_ms` | Temps de fonctionnement de l'émetteur |
| 4 | 4 | `resync_hint_seq` | Dernier `seq` reconnu |
| 8 | 36 | `padding` | Doit être rempli de `0x00` |

## Exigences de sécurité

- TLS 1.3 obligatoire avec suites AEAD (TLS_AES_128_GCM_SHA256 ou supérieures).
- Authentification mutuelle via certificats X.509 signés par l'autorité interne.
- Renouvellement de certificat automatisé et rotation avant expiration (J-15).
- Validation stricte des `session_id` : valeurs rejetées si collision avec une session active.
- Journaux d'audit pour toute tentative de négociation échouée.

## Articulation avec la télémétrie

Les trames de type télémétrie sont publiées simultanément dans le bus interne. Les consommateurs (backend analytique et app Android) exploitent les mêmes définitions de champs. Toute variation du format impose une mise à jour synchronisée des dépendances décrites ci-dessous. Les transitions de cadence (ex. passage 50 Hz → 10 Hz) déclenchent un message système `type = 0x0003` avec `resync_hint_seq = seq` pour informer la pile télémétrie et permettre un ré-échantillonnage côté backend.

## Dépendances croisées

- **Application Android**
  - Utilise `session_id` pour corréler les flux vidéo et commandes.
  - Interprète le bit `lights_override` pour afficher une alerte UI « Contrôle manuel des feux ».
  - Exploite `fail_safe_reason` pour déclencher l'écran d'arrêt d'urgence.
- **Firmware embarqué**
  - Produit les indicateurs `fail_safe` et `fail_safe_reason` ; toute activation force la mise en ralentissement à 10 Hz jusqu'à désarmement.
  - Gère le champ `lights_pattern` et la désactivation automatique lorsque `lights_override = 0`.
  - Maintient un compteur interne aligné sur `seq` pour les diagnostics ; requiert resynchronisation après un wrap 32 bits.

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
