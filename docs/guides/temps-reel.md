# Guide développeur – Interface temps réel

Ce guide fournit un point d'entrée opérationnel pour travailler sur la pile temps réel. Il s'appuie sur la [spécification détaillée de l'interface temps réel](../specs/interface-temps-reel.md) qui reste la référence normative.

## Canal de commandes et télémétrie

- Les échanges commandes/télémétrie doivent respecter la structure de trame binaire décrite dans la spécification (en-tête 64 octets, différenciation par `type`).
- Les cadences nominales et dégradées (50 Hz, 25 Hz, 10 Hz) ainsi que les conditions de bascule doivent être reproduites dans les tests d'intégration.
- Les transitions fail-safe déclenchent la publication des indicateurs `fail_safe`, `fail_safe_reason` et `fail_safe_progress` attendus par l'app Android et le backend.

## Sécurité et résilience

- Toutes les implémentations clients/serveurs doivent établir une session TLS 1.3 avec authentification mutuelle et rotation de certificats avant expiration.
- Une perte de commandes supérieure aux seuils documentés impose une resynchronisation (`resync_hint_seq`) et une mise en sécurité.

## Points d'attention pour le développement

1. **Synchronisation** : veillez à aligner `session_id`/`seq` sur l'ensemble des composants (backend, Android, firmware) pour faciliter le débogage croisé.
2. **Tolérance aux pertes** : implémentez les chemins de dégradation et documentez les tests associés (mode 25 Hz, 10 Hz).
3. **Exemples** : les exemples hexadécimaux fournis dans la spécification servent de gabarits pour les tests automatisés.

Pour toute modification de protocole, mettre à jour la spécification et communiquer les impacts aux équipes application et firmware.
