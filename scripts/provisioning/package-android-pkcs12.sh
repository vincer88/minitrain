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
