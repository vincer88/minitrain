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

- Interface priorisant la rapidité : liste linéaire sans cartes graphiques ni drag-and-drop, actions accessibles en un tap.
- Surimpression cabine plein écran semi-transparente, unifiée pour toutes les rames (absence de personnalisation visuelle spécifique par train dans la version actuelle).
- Contrôles tactiles larges (slider pleine largeur, puces à large zone d’activation) afin de convenir aux opérateurs en environnement industriel.
- Thème contrasté prévu pour rester lisible en conditions lumineuses variables, sans code couleur additionnel pour les statuts (uniquement texte et toasts).

## Parcours utilisateur détaillé

### Vue Liste multi-train

1. La page d’accueil affiche une liste chronologique des rames enregistrées (ordre d’ajout).
2. Chaque item présente : alias, état courant (`Disponible` / `En cours` / `Perdu`) et boutons texte `Contrôler`, `Détails` (optionnel futur) et `Supprimer`.
3. Le bouton `Ajouter un train` crée une rame de démonstration avec alias généré automatiquement.
4. Aucun dialogue de confirmation n’est requis lors de l’ajout ; l’alias est modifiable ultérieurement via la fiche détaillée.

### États et transitions de disponibilité

| État affiché | Préconditions | Déclencheur | Résultat visible | Notes |
|--------------|---------------|-------------|------------------|-------|
| `Disponible` | Rame non réservée, télémétrie `fail_safe = false`. | - Démarrage application.<br>- Réception d’un `pilot release` (`T₂`).<br>- Rétablissement connexion après perte, après fin de rampe `T₁`. | Bouton `Contrôler` actif. Toast « Train [alias] disponible » si retour depuis `Perdu`. | Le firmware a relâché la session et l’éclairage repasse au schéma automatique rouge. |
| `En cours` | L’opérateur local a réservé la rame. | Tap sur `Contrôler` lorsque l’état était `Disponible`. | Surimpression cabine ouverte. Statut mis à jour immédiatement. | Empêche la sélection par un autre opérateur. |
| `Perdu` | Session précédemment active. | Absence de commandes pendant `T₂` ou coupure WebSocket détectée. | Statut `Perdu` avec toast « Connexion perdue – tentative de reconnexion ». `Contrôler` désactivé jusqu’à retour `Disponible`. | La surimpression reste ouverte mais les commandes sont figées. |

### Surimpression cabine

1. Ouverture : déclenchée par `Contrôler`. La liste reste accessible derrière un panneau semi-transparent.
2. Commandes :
   - Slider horizontal de vitesse (0 à la valeur maximale configurée), retour visuel immédiat et envoi de commande à chaque changement.
   - Puce `Direction` basculant entre avant / arrière, synchronisée avec la télémétrie.
   - Puce `Cabine` changeant l’angle de vue associé (sans personnalisation graphique spécifique).
3. Bandeau supérieur : mentionne alias, état de connexion, bouton `Fermer`.
4. Fermeture : bouton `Fermer` remet en avant la liste tout en laissant la rame en `En cours` tant que l’opérateur n’a pas relâché la session ou qu’un `pilot release` (`T₂`) ne s’est pas produit.

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

- Une seule session active par rame : toute commande de contrôle exige que la rame soit `Disponible`. Les tentatives concurrentes reçoivent un refus (statut non modifiable).
- La rampe de sécurité `T₁` coupe immédiatement la propulsion tout en bloquant la ré-acquisition jusqu’à complétion (`fail_safe_progress = 100 %`).
- Les transitions d’état suivent la télémétrie : l’application ne force pas un retour `Disponible` tant que le firmware publie `fail_safe = true`.
- `Supprimer` n’est autorisé que pour les rames dont la session est inactive (`Disponible` ou `Perdu`). Si l’utilisateur tente de supprimer une rame `En cours`, afficher un message et refuser l’action.

## Notifications

- Toast lors du passage `Perdu` → `Disponible` (« Train [alias] disponible »).
- Toast lors de la perte de connexion (`En cours` → `Perdu`).
- Snackbar (ou toast long) en cas de tentative de suppression d’une rame non disponible.
- Indicateur persistant dans le bandeau cabine sur l’état de connexion (texte `Connecté` / `Déconnecté`).

## Cas limites et comportements attendus

- **Perte de connexion prolongée** : si aucune reconnexion n’est possible, l’état reste `Perdu`, les commandes sont figées et l’application tente périodiquement de rouvrir le canal. L’opérateur est invité à fermer la cabine ou à supprimer la rame si autorisé.
- **Retour automatique à `Disponible`** : après récupération réseau et fin de rampe `T₁`, l’application bascule automatiquement l’état et rejoue le toast de disponibilité.
- **Absence de personnalisation cabine** : toutes les rames partagent la même surimpression générique. Les flux vidéo ou overlays spécifiques sont ignorés tant que la future personnalisation n’est pas livrée.
- **Suppression pendant reconnexion** : si l’utilisateur supprime une rame `Perdu` pendant la tentative de reconnexion, la suppression doit annuler toute tentative de reprise et retirer les ressources associées.
- **Navigation système Android** : si l’application passe en arrière-plan, la session reste réservée (état `En cours`). Au retour, l’écran doit refléter l’état courant (potentiellement `Perdu` ou `Disponible`).

