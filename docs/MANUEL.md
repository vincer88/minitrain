# Guide utilisateur – minitrain

Bienvenue dans l'application minitrain. Ce guide présente le produit, les prérequis pour y accéder et les étapes à suivre pour gérer vos trains en toute sécurité.

## 1. À propos de l'application

minitrain permet à un opérateur de visualiser l'ensemble des rames disponibles, de réserver un train et de piloter sa vitesse ainsi que son sens de marche via une interface tablette ou pupitre Android.

## 2. Pré-requis d'accès

- L'application minitrain doit être installée et provisionnée sur votre appareil par votre équipe IT.
- Assurez-vous d'être connecté au réseau interne autorisé et de disposer de vos identifiants personnels.
- Vérifiez que votre appareil a reçu les certificats de sécurité nécessaires (provisionnement géré par l'IT).

## 3. Parcours opérateur

### 3.1 Ajouter un train de démonstration

1. Ouvrez l'application. La page d'accueil affiche la liste des rames déjà connues.
2. Touchez **Ajouter un train** pour créer une rame de démonstration. Elle apparaît en fin de liste avec un alias généré automatiquement.【F:docs/specs/android.md†L32-L46】【F:docs/specs/android.md†L74-L84】

### 3.2 Comprendre les états affichés

Chaque rame présente un état textuel directement issu de la télémétrie :

- **Disponible** : la rame peut être réservée immédiatement. L'application affiche un toast de confirmation lors du retour à cet état après une perte de connexion.【F:docs/specs/android.md†L32-L84】
- **En cours** : vous contrôlez actuellement la rame depuis votre appareil. La surimpression cabine reste active même si vous fermez le panneau.【F:docs/specs/android.md†L47-L67】【F:docs/specs/android.md†L85-L102】
- **Perdu** : la connexion a été interrompue ou les commandes n'ont pas été reçues pendant un délai prolongé. Les commandes sont figées jusqu'à ce que la rame redevienne **Disponible**.【F:docs/specs/android.md†L47-L67】【F:docs/specs/android.md†L85-L108】

### 3.3 Prendre le contrôle d'un train

1. Identifiez une rame marquée **Disponible**.
2. Touchez **Contrôler**. L'état passe à **En cours** et la cabine s'affiche en plein écran semi-transparent.【F:docs/specs/android.md†L47-L84】【F:docs/specs/android.md†L85-L102】
3. Ajustez la vitesse via le curseur horizontal. Utilisez les puces **Direction** et **Cabine** pour modifier le sens de marche et l'angle de vue.【F:docs/specs/android.md†L85-L102】
4. Pour fermer la cabine sans libérer la rame, touchez **Fermer**. L'état reste **En cours** jusqu'à libération automatique ou manuelle.【F:docs/specs/android.md†L95-L102】

### 3.4 Relâcher une rame

- Appuyez sur l'action **Relâcher** (si disponible) ou laissez la session expirer automatiquement. Lorsque le firmware déclenche un *pilot release*, l'état repasse à **Disponible** et le bouton **Contrôler** redevient actif.【F:docs/specs/android.md†L47-L84】

## 4. Pilotage et indicateurs visuels

- Surveillez les messages contextuels (toasts) pour connaître les pertes ou reprises de connexion.【F:docs/specs/android.md†L117-L132】
- Les feux du train suivent la logique automatique décrite dans l'interface temps réel. Une activation fail-safe maintient les feux de sécurité jusqu'au retour à un état stable.【F:docs/specs/interface-temps-reel.md†L1-L84】【F:docs/specs/interface-temps-reel.md†L86-L132】

## 5. Bonnes pratiques de sécurité

- Utilisez uniquement un appareil provisionné et verrouillez-le lorsque vous ne l'utilisez pas.
- Ne partagez pas vos identifiants. Chaque opérateur doit disposer de son propre compte pour tracer les actions.【F:docs/specs/android.md†L17-L29】
- Vérifiez régulièrement que la connexion TLS est établie (statut **Connecté** dans la cabine). En cas d'alerte, suivez la procédure de dépannage ci-dessous.【F:docs/specs/android.md†L117-L132】【F:docs/specs/interface-temps-reel.md†L1-L44】
- Respectez les consignes internes de renouvellement de certificats et de rotation des appareils provisionnés.

## 6. Aide et dépannage

### Perte de connexion ou état **Perdu**

1. Vérifiez la couverture réseau locale et restez sur place le temps que l'application tente la reconnexion automatique.
2. Si l'état ne revient pas à **Disponible** après quelques secondes, contactez le support afin qu'il vérifie le canal temps réel (WebSocket/QUIC). Les seuils de reprise sont gérés automatiquement par le firmware.【F:docs/specs/android.md†L47-L84】【F:docs/specs/interface-temps-reel.md†L44-L84】

### Comprendre les feux et le fail-safe

- Feux rouges bilatéraux : rame disponible sans cabine sélectionnée.
- Feu blanc côté cabine et rouge opposé : rame en marche avant.
- Inversion des couleurs : marche arrière.
- Feux maintenus en sécurité et commandes figées : fail-safe actif pendant la rampe de protection. Attendez que la progression atteigne 100 % avant de reprendre la rame.【F:docs/specs/interface-temps-reel.md†L86-L132】

### En cas d'alarme ou d'impossibilité de relâcher une rame

- Fermez la cabine et signalez l'incident. Le support peut déclencher un relâchement forcé ou vérifier la télémétrie.
- Ne tentez pas de supprimer une rame tant que l'état est **En cours** pour éviter un conflit de session.【F:docs/specs/android.md†L103-L116】

## 7. Ressources complémentaires

- Pour les procédures de build et de tests, consultez les guides développeur situés dans `docs/guides/`.
- Pour la référence complète du protocole temps réel et des états, reportez-vous aux spécifications dans `docs/specs/`.
