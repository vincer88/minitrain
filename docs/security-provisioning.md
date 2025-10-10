# Secure Provisioning and Credential Rotation

This document describes how to provision the credentials required by the firmware and the Android
application to establish mutually-authenticated TLS (`mTLS`) sessions over secure WebSockets.

## Firmware provisioning

1. Generate a client key pair and certificate signed by the control-plane CA.
2. Encode the CA certificate, client certificate, and private key using PEM.
3. Provide the following Kconfig entries via `menuconfig` or environment variables during the
   firmware build:

   | Setting | Description |
   | ------- | ----------- |
   | `CONFIG_MINITRAIN_WSS_URI` | Secure WebSocket endpoint (`wss://…`). |
   | `CONFIG_MINITRAIN_EXPECTED_HOST` | Hostname that must be present in the certificate CN/SAN. |
   | `CONFIG_MINITRAIN_CA_CERT_PEM` | Trusted root/issuing CA certificate. |
   | `CONFIG_MINITRAIN_CLIENT_CERT_PEM` | Device client certificate. |
   | `CONFIG_MINITRAIN_CLIENT_KEY_PEM` | Device private key. |

4. For local development the same values can be supplied via environment variables with matching
   names (e.g. `MINITRAIN_CA_CERT_PEM`).
5. Secrets are stored outside of source control; tooling such as `espsecure.py` or factory
   provisioning scripts should inject them just before flashing.
6. Rotation: deploy a new certificate/key pair, update the config entry, restart the device, then
   revoke the previous credential.

## Android application provisioning

1. Import the client certificate and private key into a password-protected PKCS#12 container.
2. Deploy the container to the device through your MDM or provisioning pipeline and register it in
   the Android KeyChain/KeyStore.
3. Configure a small wrapper implementing `MtlsCredentialStore` that loads the PKCS#12 container and
   exposes the `X509KeyManager`/`X509TrustManager`.
4. Supply OAuth2 parameters (`client_id`, token endpoint, scopes) via encrypted configuration (e.g.
   Android `Secrets Gradle Plugin` or runtime configuration service).
5. Tokens are stored using encrypted shared preferences and refreshed at least 12 hours before
   expiry; revoke credentials by clearing the storage and re-provisioning the PKCS#12 container.

## Secret rotation flow

1. Issue new certificates for firmware and mobile clients.
2. Deliver credentials via the provisioning pipelines described above.
3. Perform a staged rollout and monitor the integration test suite against the TLS staging server.
4. Once all clients confirm the new credentials, revoke and delete the previous certificates.
