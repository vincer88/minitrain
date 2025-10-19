# Spécification fonctionnelle – Application Android (vue opérateur multi-train)

## Objectifs

- Permettre à un opérateur mobile de réserver rapidement une rame disponible et de piloter les commandes essentielles (vitesse, direction, cabine) sans configuration préalable.
- Garantir la visibilité instantanée de l’état de disponibilité des rames (`Disponible`, `En cours`, `Perdu`) et des transitions automatiques imposées par le firmware (`T₁` et `T₂`).
- Assurer un comportement homogène avec le firmware ESP32, notamment le retour automatique à l’état `Disponible` et l’affichage de notifications informant l’opérateur des pertes/reprises de contrôle.

## Acteurs

- **Opérateur terrain** : pilote les trains depuis une tablette ou un pupitre Android.
- **Application Android** : interface de contrôle, gère l’état des rames, les interactions UI et les notifications.
- **Firmware train** : source de télémétrie temps réel et destinataire des commandes.
- **Backend temps réel** (websocket/quic) : relai sécurisé pour les commandes et la télémétrie.

## Hypothèses UX

- **AN-NF-01** : L’application doit présenter une liste linéaire sans cartes graphiques ni gestes de drag-and-drop afin de garantir qu’une action soit accessible en un seul tap.
- **AN-NF-02** : L’application doit afficher une surimpression cabine plein écran semi-transparente identique pour toutes les rames afin d’éviter toute personnalisation visuelle spécifique dans cette version.
- **AN-NF-03** : L’application doit proposer des contrôles tactiles larges, incluant un slider pleine largeur et des puces à grande zone d’activation, pour assurer une manipulation fiable en environnement industriel.
- **AN-NF-04** : L’application doit utiliser un thème contrasté lisible en conditions lumineuses variables sans recourir à un code couleur additionnel pour les statuts, en se limitant à l’affichage texte et aux toasts.

## Parcours utilisateur détaillé

### Vue Liste multi-train

1. **AN-RF-08** : L’application doit afficher sur la page d’accueil une liste chronologique des rames enregistrées selon leur ordre d’ajout.
2. **AN-RF-09** : L’application doit présenter pour chaque rame son alias, son état courant (`Disponible`, `En cours` ou `Perdu`) et les boutons texte `Contrôler`, `Détails` (optionnel futur) et `Supprimer`.
3. **AN-RF-10** : L’application doit permettre de créer une rame de démonstration avec alias généré automatiquement lorsque l’opérateur active le bouton `Ajouter un train`.
4. **AN-RF-11** : L’application ne doit pas afficher de dialogue de confirmation à l’ajout d’une rame et doit permettre la modification ultérieure de l’alias depuis la fiche détaillée.

### États et transitions de disponibilité

| État affiché | Préconditions | Déclencheur | Résultat visible | Notes |
|--------------|---------------|-------------|------------------|-------|
| `Disponible` | Rame non réservée, télémétrie `fail_safe = false`. | - Démarrage application.<br>- Réception d’un `pilot release` (`T₂`).<br>- Rétablissement connexion après perte, après fin de rampe `T₁`. | Bouton `Contrôler` actif. Toast « Train [alias] disponible » si retour depuis `Perdu`. | Le firmware a relâché la session et l’éclairage repasse au schéma automatique rouge. |
| `En cours` | L’opérateur local a réservé la rame. | Tap sur `Contrôler` lorsque l’état était `Disponible`. | Surimpression cabine ouverte. Statut mis à jour immédiatement. | Empêche la sélection par un autre opérateur. |
| `Perdu` | Session précédemment active. | Absence de commandes pendant `T₂` ou coupure WebSocket détectée. | Statut `Perdu` avec toast « Connexion perdue – tentative de reconnexion ». `Contrôler` désactivé jusqu’à retour `Disponible`. | La surimpression reste ouverte mais les commandes sont figées. |

### Surimpression cabine

1. **AN-RF-12** : L’application doit ouvrir la surimpression cabine lorsqu’un opérateur sélectionne `Contrôler`, tout en conservant la liste visible derrière un panneau semi-transparent.
2. **AN-RF-13** : L’application doit proposer un slider horizontal de vitesse couvrant l’intervalle 0 à la valeur maximale configurée, avec retour visuel immédiat et envoi de commande à chaque changement.
3. **AN-RF-14** : L’application doit fournir une puce `Direction` permettant de basculer entre les modes avant et arrière en restant synchronisée avec la télémétrie.
4. **AN-RF-15** : L’application doit offrir une puce `Cabine` qui ajuste l’angle de vue associé sans appliquer de personnalisation graphique spécifique.
5. **AN-RF-16** : L’application doit afficher dans le bandeau supérieur de la surimpression l’alias de la rame, l’état de connexion et un bouton `Fermer`.
6. **AN-RF-17** : L’application doit, lorsque l’opérateur actionne le bouton `Fermer`, ramener la liste au premier plan tout en conservant la rame à l’état `En cours` jusqu’à la libération de session ou réception d’un `pilot release` (`T₂`).

## Exigences fonctionnelles testables

| ID | Description | Préconditions | Déclencheur | Résultat attendu |
|----|-------------|---------------|-------------|------------------|
| RF-01 | Création rapide d’une rame de démonstration. | Vue liste affichée. | Tap sur `Ajouter un train`. | Une nouvelle rame apparaît en fin de liste avec alias auto-généré et statut `Disponible`. |
| RF-02 | Transition vers la cabine et réservation. | Rame affichée `Disponible`. | Tap sur `Contrôler`. | La surimpression s’affiche, le statut passe à `En cours`, la rame est réservée localement. |
| RF-03 | Affichage du statut `Perdu`. | Rame en `En cours`. | Perte WebSocket ou absence de commande > `T₂`. | Le statut passe à `Perdu`, les commandes sont désactivées, un toast informe l’opérateur. |
| RF-04 | Retour automatique à `Disponible`. | Rame en `Perdu` après perte connexion. | Reprise télémétrie valide et fin de rampe `T₁`. | Le statut redevient `Disponible`, un toast confirme la disponibilité, `Contrôler` réactivé. |
| RF-05 | Fermeture cabine sans relâcher la session. | Surimpression ouverte (`En cours`). | Tap sur `Fermer`. | Retour à la liste avec la rame toujours marquée `En cours` jusqu’à relâchement automatique/manuelle. |
| RF-06 | Suppression d’une rame. | Rame listée. | Tap sur `Supprimer`. | L’item disparaît immédiatement, libérant la session s’il était `Disponible`. |
| RF-07 | Absence de personnalisation cabine. | Application installée. | Consultation cabine depuis n’importe quelle rame. | La même surimpression générique est affichée, sans habillage spécifique. |

## Règles métier

- **AN-RB-01** : L’application doit refuser toute commande de contrôle sur une rame non `Disponible` afin de garantir une unique session active par rame et maintenir le statut inchangé en cas de tentative concurrente.
- **AN-RB-02** : L’application doit imposer l’arrêt immédiat de la propulsion et bloquer la ré-acquisition tant que la rampe de sécurité `T₁` n’est pas complétée (`fail_safe_progress = 100 %`).
- **AN-RB-03** : L’application doit aligner les transitions d’état sur la télémétrie et s’abstenir de forcer un retour `Disponible` tant que le firmware publie `fail_safe = true`.
- **AN-RB-04** : L’application doit interdire la suppression d’une rame dont la session est `En cours`, afficher un message de refus et n’autoriser la suppression que pour les rames `Disponible` ou `Perdu`.

## Notifications

- **AN-RF-18** : L’application doit afficher un toast « Train [alias] disponible » lors de la transition d’une rame de `Perdu` à `Disponible`.
- **AN-RF-19** : L’application doit afficher un toast d’alerte lors du passage d’une rame de `En cours` à `Perdu` à la suite d’une perte de connexion.
- **AN-RF-20** : L’application doit présenter une snackbar ou un toast long pour informer l’opérateur qu’une tentative de suppression d’une rame non disponible est refusée.
- **AN-RF-21** : L’application doit maintenir un indicateur persistant dans le bandeau cabine signalant l’état de connexion avec le texte `Connecté` ou `Déconnecté`.

## Cas limites et comportements attendus

- **AN-RF-22** : L’application doit maintenir l’état `Perdu`, figer les commandes et relancer périodiquement la reconnexion tant qu’une perte de connexion prolongée persiste, tout en suggérant à l’opérateur de fermer la cabine ou de supprimer la rame si cela est permis.
- **AN-RF-23** : L’application doit, après récupération réseau et complétion de la rampe `T₁`, rétablir automatiquement l’état `Disponible` et réafficher le toast de disponibilité.
- **AN-RF-24** : L’application doit conserver une surimpression cabine générique pour toutes les rames et ignorer les flux vidéo ou overlays spécifiques tant que la personnalisation n’est pas livrée.
- **AN-RF-25** : L’application doit, lorsque l’opérateur supprime une rame `Perdu` pendant une tentative de reconnexion, annuler la reprise en cours et libérer les ressources associées.
- **AN-RF-26** : L’application doit maintenir une session `En cours` lorsque l’application passe en arrière-plan et réafficher à son retour l’état courant de la rame, qu’il soit `Perdu` ou `Disponible`.

## Matrice exigences-tests

| ID | Type de test recommandé | Description du test associé |
|----|-------------------------|------------------------------|
| AN-NF-01 | Inspection UX | Vérifier que la liste reste linéaire, sans cartes ni drag-and-drop, et que chaque action est réalisable en un tap. |
| AN-NF-02 | Inspection UX | Confirmer que la surimpression cabine est uniforme et semi-transparente pour l’ensemble des rames. |
| AN-NF-03 | Tests d’ergonomie | Mesurer la largeur des contrôles tactiles et valider leur utilisation avec des gants industriels. |
| AN-NF-04 | Inspection visuelle | Observer l’application sous différents éclairages pour s’assurer du contraste et de l’absence de code couleur additionnel. |
| AN-RF-08 | Test fonctionnel UI | Démarrer l’application et vérifier l’ordre chronologique d’affichage des rames. |
| AN-RF-09 | Test fonctionnel UI | Contrôler qu’un item de liste expose alias, état et les boutons requis. |
| AN-RF-10 | Test fonctionnel UI | Activer `Ajouter un train` et confirmer la création d’une rame de démonstration avec alias auto-généré. |
| AN-RF-11 | Test fonctionnel UI | Ajouter une rame et vérifier l’absence de dialogue de confirmation puis modifier l’alias via la fiche détaillée. |
| AN-RF-12 | Test fonctionnel UI | Appuyer sur `Contrôler` et confirmer l’ouverture de la surimpression avec la liste visible en arrière-plan. |
| AN-RF-13 | Test fonctionnel UI | Manipuler le slider de vitesse et valider le retour visuel et l’émission de commandes à chaque mouvement. |
| AN-RF-14 | Test fonctionnel UI | Utiliser la puce `Direction` et vérifier la synchronisation avec la télémétrie. |
| AN-RF-15 | Test fonctionnel UI | Activer la puce `Cabine` et confirmer le changement d’angle sans personnalisation graphique. |
| AN-RF-16 | Inspection UI | Vérifier que le bandeau supérieur affiche alias, état de connexion et bouton `Fermer`. |
| AN-RF-17 | Test fonctionnel UI | Fermer la surimpression et confirmer que la rame reste `En cours` jusqu’au relâchement de session. |
| AN-RB-01 | Test d’intégration | Tenter de contrôler une rame non `Disponible` et vérifier le refus et la stabilité du statut. |
| AN-RB-02 | Test d’intégration | Simuler l’activation de `T₁` et vérifier l’arrêt propulsion et le blocage de ré-acquisition jusqu’à 100 %. |
| AN-RB-03 | Test d’intégration | Forcer `fail_safe = true` côté firmware et confirmer que l’application n’impose pas le retour `Disponible`. |
| AN-RB-04 | Test fonctionnel UI | Essayer de supprimer une rame `En cours` et vérifier l’affichage du message de refus et la persistance de la rame. |
| AN-RF-18 | Test fonctionnel UI | Observer le toast de disponibilité lors du retour `Perdu` → `Disponible`. |
| AN-RF-19 | Test fonctionnel UI | Provoquer une coupure réseau et confirmer le toast lors de `En cours` → `Perdu`. |
| AN-RF-20 | Test fonctionnel UI | Tenter de supprimer une rame non disponible et vérifier la snackbar ou le toast long de refus. |
| AN-RF-21 | Inspection UI | Contrôler l’indicateur d’état de connexion dans le bandeau cabine. |
| AN-RF-22 | Test de résilience | Simuler une perte de connexion prolongée et vérifier la persistance de l’état `Perdu` et les tentatives de reconnexion. |
| AN-RF-23 | Test de résilience | Restaurer la connexion après `T₁` et confirmer le retour automatique à `Disponible` et le toast associé. |
| AN-RF-24 | Inspection UI | Parcourir plusieurs rames et vérifier l’absence de personnalisation cabine. |
| AN-RF-25 | Test de résilience | Supprimer une rame `Perdu` pendant la reconnexion et vérifier la libération des ressources et l’arrêt des tentatives. |
| AN-RF-26 | Test fonctionnel UI | Envoyer l’application en arrière-plan puis la ramener pour confirmer la conservation de la session et la mise à jour d’état. |

