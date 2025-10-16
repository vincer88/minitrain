# Guide de déploiement minitrain

## Objectif et périmètre
Minitrain combine un firmware C++17 pour ESP32 et une application Android Ktor pour piloter un mini-train via WebSocket sécurisé et OAuth2 mTLS.

Ce guide décrit comment provisionner les secrets et orchestrer leur rotation afin que les deux composants puissent établir une session TLS mutuellement authentifiée.



## Pré-requis
- Une autorité de certification interne (CA) capable de signer des certificats clients.
- Un environnement de build CMake ≥ 3.16 avec toolchain C++17 et la toolchain ESP, ainsi que Java 17+ et Gradle pour l'application Android.

- Accès aux pipelines de provisioning (usine ou MDM) et à un coffre-fort de secrets.

## Inventaire des secrets
- URI du WebSocket sécurisé (`CONFIG_MINITRAIN_WSS_URI` / `MINITRAIN_WSS_URI`).
- Hôte attendu (`CONFIG_MINITRAIN_EXPECTED_HOST` / `MINITRAIN_EXPECTED_HOST`), déduit automatiquement à partir de l'URI si absent.

- Certificat d'autorité racine (CA), certificat client et clé privée du firmware.

- Conteneur PKCS#12 contenant certificat et clé pour l'application Android.



- Paramètres OAuth2 (client_id, endpoint, scope) et stratégie de stockage/renouvellement des jetons.



## Chaîne de provisionnement firmware
1. Générez un couple clé/certificat client signé par votre CA de contrôle et exportez les trois fichiers PEM (CA, client, clé).


2. Exécutez `scripts/provisioning/generate-fw-credentials.sh` (voir annexe) avec `CA_CERT`, `CA_KEY`, `WSS_URI`, `DEVICE_ID` et, si besoin, `EXPECTED_HOST` ou `VALIDITY_DAYS`. Le script produit le certificat client, la clé privée et un fichier `firmware-credentials.env` contenant les exports `MINITRAIN_*` à sourcer avant la compilation ou à injecter via vos jobs CI.
3. Pour un build Zephyr/ESP-IDF, importez les PEM via `menuconfig` dans les entrées `CONFIG_MINITRAIN_*`, ou sourcer `firmware-credentials.env` avant `cmake`/`ninja` pour que les valeurs d'environnement soient consommées par `loadTlsCredentialConfig()` pendant la construction ou l'exécution.


4. Lors du flashage, laissez vos scripts d'usine (ex. `espsecure.py`) copier les PEM générés dans un espace temporaire, injecter les variables d'environnement et effacer les artefacts une fois l'écriture terminée.


5. Documentez la date d'expiration et planifiez la rotation : générez un nouveau couple, mettez à jour l'URI si nécessaire, redémarrez le firmware puis révoquez l'ancien certificat via votre PKI.



## Chaîne de provisionnement Android
1. Reprenez le certificat et la clé du firmware (ou générez une variante dédiée) et empaquetez-les dans un PKCS#12 protégé par mot de passe à l'aide du script `scripts/provisioning/package-android-pkcs12.sh`. Celui-ci crée le fichier `.p12`, stocke le mot de passe à part et peut pousser l'archive sur un appareil via `adb` si `ADB_PUSH=true` et `ANDROID_SERIAL` sont définis.
2. Déployez le PKCS#12 par votre MDM ou en l'important dans le KeyStore Android, puis initialisez `KeystoreMtlsCredentialStore` avec les octets du conteneur et son mot de passe ; la classe expose les gestionnaires TLS consommés par la pile Ktor.



3. Renseignez les paramètres OAuth2 (endpoint, `client_id`, `scope`) dans votre service de configuration chiffré ou via `gradle.properties` chiffré injecté à l'exécution ; `OAuth2MtlsTokenProvider` s'appuie dessus pour obtenir/rafraîchir les jetons mTLS.


4. Les jetons sont stockés et révoqués via `SecureTokenStorage`, qui s'appuie sur les préférences chiffrées Android ; prévoyez une tâche de nettoyage lors d'une rotation de certificats ou d'une révocation d'accès.


5. Vérifiez que `SecureWebSocketClientFactory` reçoit bien `expectedHostname` (identique à celui du firmware) afin de renforcer la validation d'hôte côté client.



## Rotation et audit
1. Émettez en parallèle les nouvelles paires de certificats pour firmware et clients mobiles, distribuez-les via les scripts ci-dessus et mettez à jour les pipelines de build.


2. Lancez une montée en charge progressive vers l'endpoint TLS de staging, surveillez les journaux Ktor/ESP32 et vérifiez la validation d'hôte.
3. Une fois tous les clients migrés, révoquez les anciens certificats et purgez les secrets (fichiers `.env`, `.p12`, mots de passe) des postes de build et des appareils.



## Paramétrage des timeouts de commande et validation de la disponibilité
### Firmware
1. Ajustez les nouveaux seuils en CMake en positionnant `-DMINITRAIN_FAILSAFE_THRESHOLD_MS=<ms>` (délais avant fail-safe) et `-DMINITRAIN_FAILSAFE_RAMP_MS=<ms>` (durée de la rampe d'arrêt) lors de la génération (`cmake -S firmware -B firmware/build -DMINITRAIN_FAILSAFE_THRESHOLD_MS=300 -DMINITRAIN_FAILSAFE_RAMP_MS=1200`). Pour ESP-IDF/Zepyhr, les mêmes symboles sont exposés dans `menuconfig` sous *Minitrain → Command timeout*.
2. Générez et flashez le firmware. Vérifiez dans les journaux série que le contrôleur annonce les seuils attendus au démarrage. Conservez un tableau de correspondance train → valeur de timeout pour tracer les déviations lors des audits.

### Services back-end
1. Publiez la valeur équivalente côté orchestrateur de commandes via vos variables d'environnement ou Helm charts : `MINITRAIN_COMMAND_TIMEOUT_MS` (fail-safe firmware) et `MINITRAIN_PILOT_RELEASE_MS` (fenêtre d'abandon opérateur). Les services doivent rejeter toute commande dont l'horodatage dépasse ces seuils pour rester cohérents avec le firmware.
2. Redémarrez les services concernés et faites valider la configuration via les health-checks `/status` (les réponses doivent exposer les nouveaux seuils dans la section `commandTimeouts`). Archivez la sortie pour le dossier de déploiement.

### Validation de la signalisation de disponibilité (staging)
1. Poussez au moins deux rames instrumentées sur l'environnement de staging et forcez une rotation des pilotes (session WebSocket interrompue) pour vérifier que l'indicateur *Disponible* bascule côté application et que le bus d'événements publie l'état `AVAILABLE` en moins de 3 s.
2. Soumettez une commande expirée depuis un script de test ; contrôlez que la télémétrie signale `fail_safe` et que la bannière de disponibilité repasse à *Indisponible* jusqu'à réception d'une commande fraîche.
3. En fin de boucle, capturez les métriques Prometheus/OTEL `command_timeout_trigger_total` et `train_availability_state` pour confirmer qu'elles reflètent les tests effectués avant d'autoriser la promotion vers la production.

## Initialisation de l'annuaire des trains mobiles
1. Préparez la configuration distante (Remote Config ou Feature Flags) avec la liste des rames (`train_id`, alias, endpoints commande/télémétrie, seuils personnalisés). Exportez ce catalogue dans le coffre-fort avant le déploiement.
2. Lors de la première mise en service, lancez le mode d'enrôlement QR de l'application (Menu → *Ajouter un train via QR*). Chaque code doit encapsuler l'identifiant de rame, l'URL du WebSocket et l'éventuel timeout personnalisé. Scannez et validez que l'application synchronise ces données dans l'annuaire local.
3. Pour les sites sans QR, utilisez la commande d'amorçage `adb shell am broadcast -a com.minitrain.SEED_DIRECTORY --es payload @/sdcard/minitrain/seeds.json` contenant l'export Remote Config.

### Suivi opérateur pendant le déploiement
- Affichez le tableau de bord *Connectivity* (télémétrie WebSocket + métriques `last_command_timestamp`) et conservez l'onglet *Annuaire* de l'application ouvert sur une tablette de contrôle.
- Pendant la fenêtre de déploiement, chaque opérateur vérifie toutes les 5 minutes que les trains restent en état *Connecté* et que la dernière commande date de moins de la moitié du timeout défini. Toute dérive déclenche l'escalade vers le support réseau.
- Activez les alertes push/Slack `train-availability` pour recevoir les transitions `DEGRADED` et `UNAVAILABLE`. Documentez les occurrences dans le rapport de déploiement.

## Vérifications et tests
### Firmware
- `cmake -S firmware -B firmware/build && cmake --build firmware/build && ctest --test-dir firmware/build` pour confirmer que les variables `MINITRAIN_*` et les nouveaux `MINITRAIN_FAILSAFE_*` sont résolus et que la pile TLS/timeout fonctionne avec les secrets injectés.
- Test manuel : injectez une commande vieillie via le simulateur pour vérifier que le fail-safe s'enclenche au nouveau seuil et que la télémétrie publie l'état d'indisponibilité.

### Application Android
- `cd android-app && gradle test` pour valider la logique réseau, OAuth2 et la propagation des seuils côté Remote Config.
- `cd android-app && gradle connectedAndroidTest` pour établir un dialogue mTLS complet contre le serveur TLS de test et vérifier la bascule d'état *Disponible*/*Indisponible* dans l'annuaire multi-train.

### Parc multi-train (end-to-end)
- Instanciez au minimum deux rames et une passerelle backend sur staging. Lancez un scénario de pilotage simultané, forcez une expiration de commande sur une rame et vérifiez que seule cette rame passe en fail-safe tandis que l'autre reste contrôlable.
- Validez que l'historique d'événements agrégé (firmware + backend + app) reflète les transitions synchronisées (`command_timeout_triggered`, `pilot_release_started`, `train_state=AVAILABLE/UNAVAILABLE`).
- Terminez par un redémarrage de l'application mobile pour confirmer que l'annuaire se resynchronise automatiquement et que les seuils de timeout se réappliquent à la reconnexion.

## Annexes
### `scripts/provisioning/generate-fw-credentials.sh`
```bash
#!/usr/bin/env bash
set -euo pipefail

: "${CA_CERT:?Chemin du certificat CA requis}"
: "${CA_KEY:?Chemin de la clé privée de la CA requis}"
: "${WSS_URI:?URI WebSocket sécurisé requis}"

DEVICE_ID=${DEVICE_ID:-minitrain-device}
OUT_DIR=${OUT_DIR:-provisioning/firmware/${DEVICE_ID}}
VALIDITY_DAYS=${VALIDITY_DAYS:-365}

EXPECTED_HOST=${EXPECTED_HOST:-$(python3 - <<'PY' "$WSS_URI"
import sys
from urllib.parse import urlparse
uri = sys.argv[1]
host = urlparse(uri).hostname
if not host:
    raise SystemExit("Impossible de déduire l'hôte à partir de WSS_URI")
print(host, end="")
PY
)}

mkdir -p "$OUT_DIR"

openssl genrsa -out "$OUT_DIR/${DEVICE_ID}.key.pem" 3072
openssl req -new -key "$OUT_DIR/${DEVICE_ID}.key.pem" \
  -out "$OUT_DIR/${DEVICE_ID}.csr.pem" \
  -subj "/CN=${DEVICE_ID}"
openssl x509 -req -in "$OUT_DIR/${DEVICE_ID}.csr.pem" \
  -CA "$CA_CERT" -CAkey "$CA_KEY" -CAcreateserial \
  -CAserial "$OUT_DIR/${DEVICE_ID}.srl" \
  -out "$OUT_DIR/${DEVICE_ID}.crt.pem" \
  -days "$VALIDITY_DAYS" -sha256 \
  -extfile <(printf "subjectAltName=DNS:%s\nextendedKeyUsage=clientAuth\n" "$EXPECTED_HOST")

cp "$CA_CERT" "$OUT_DIR/ca.crt.pem"

python3 - <<'PY' "$OUT_DIR/firmware-credentials.env" "$OUT_DIR/ca.crt.pem" "$OUT_DIR/${DEVICE_ID}.crt.pem" "$OUT_DIR/${DEVICE_ID}.key.pem" "$WSS_URI" "$EXPECTED_HOST"
import sys, pathlib
env_path, ca_path, cert_path, key_path, wss_uri, expected = sys.argv[1:]
materials = [
    ("MINITRAIN_CA_CERT_PEM", ca_path),
    ("MINITRAIN_CLIENT_CERT_PEM", cert_path),
    ("MINITRAIN_CLIENT_KEY_PEM", key_path),
]
with open(env_path, "w", encoding="utf-8") as fh:
    fh.write("# À sourcer avant la compilation du firmware\n")
    fh.write(f"export MINITRAIN_WSS_URI='{wss_uri}'\n")
    fh.write(f"export MINITRAIN_EXPECTED_HOST='{expected}'\n")
    for name, path in materials:
        content = pathlib.Path(path).read_text()
        fh.write(f"export {name}=$(cat <<'EOF'\n{content}\nEOF\n)\n")
print(env_path)
PY

rm "$OUT_DIR/${DEVICE_ID}.csr.pem" "$OUT_DIR/${DEVICE_ID}.srl"
echo "Identifiants générés dans $OUT_DIR"
```

### `scripts/provisioning/package-android-pkcs12.sh`
```bash
#!/usr/bin/env bash
set -euo pipefail

: "${CLIENT_CERT:?Chemin du certificat client requis}"
: "${CLIENT_KEY:?Chemin de la clé privée client requise}"
: "${CA_CERT:?Chemin du certificat CA requis}"

ALIAS=${ALIAS:-minitrain-client}
OUT_DIR=${OUT_DIR:-provisioning/android}
P12_PASSWORD=${P12_PASSWORD:-$(openssl rand -base64 24)}

mkdir -p "$OUT_DIR"

openssl pkcs12 -export \
  -in "$CLIENT_CERT" \
  -inkey "$CLIENT_KEY" \
  -certfile "$CA_CERT" \
  -name "$ALIAS" \
  -out "$OUT_DIR/${ALIAS}.p12" \
  -passout pass:"$P12_PASSWORD"

printf '%s\n' "$P12_PASSWORD" > "$OUT_DIR/${ALIAS}.p12.pass"
chmod 600 "$OUT_DIR/${ALIAS}.p12.pass"

if [[ "${ADB_PUSH:-false}" == "true" ]]; then
  : "${ANDROID_SERIAL:?Définir ANDROID_SERIAL pour cibler l'appareil}"
  adb -s "$ANDROID_SERIAL" push "$OUT_DIR/${ALIAS}.p12" "/sdcard/${ALIAS}.p12"
fi

cat <<EOF
PKCS#12 généré dans $OUT_DIR/${ALIAS}.p12
Mot de passe sauvegardé dans $OUT_DIR/${ALIAS}.p12.pass
EOF
```

