# Guide développeur – Plan de migration vers le canal temps réel

Ce document décrit les étapes pour achever la transition des échanges HTTP périodiques historiques vers le canal temps réel sécurisé. Les exigences fonctionnelles du protocole sont détaillées dans la [spécification de l'interface temps réel](../specs/interface-temps-reel.md).

## Objectifs

- Remplacer les sondes HTTP existantes par la session binaire temps réel (WebSocket TLS ou QUIC).
- Garantir l'exposition des états `Disponible`, `En cours`, `Perdu` et des indicateurs `fail_safe` attendus par les applications clientes.
- Retirer l'ancienne pile HTTP une fois les validations conclues.

## Étapes recommandées

1. **Phase hybride** : intégrer un client WebSocket dans le firmware ESP32 tout en conservant la télémétrie HTTP en lecture seule pour monitoring.
2. **Validation croisée** : comparer la cadence, les trames et les transitions d'état observées via les deux canaux afin de s'assurer que la mise en œuvre respecte la spécification (cadences 50/25/10 Hz, séquences de fail-safe, champs de télémétrie).
3. **Basculer la production** : activer la diffusion exclusive via le canal temps réel et désactiver les commandes HTTP. Documenter les impacts pour les applications Android et outils backend.
4. **Nettoyage** : retirer le code HTTP résiduel et mettre à jour la documentation des tests pour ne plus référencer l'ancien protocole.

## Vérifications

- Mettre à jour ou créer des tests d'intégration qui valident les exemples de trames fournis par la spécification.
- Confirmer que les politiques de sécurité TLS et la rotation des certificats sont en place.
- Communiquer aux équipes applicatives les changements de dépendances (endpoints, ports, certificats) avant mise en production.
