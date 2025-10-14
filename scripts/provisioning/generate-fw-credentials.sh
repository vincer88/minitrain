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
