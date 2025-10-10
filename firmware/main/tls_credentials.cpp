#include "tls_credentials.hpp"

#include <cstdlib>
#include <regex>
#include <stdexcept>
#include <string>

#ifdef ESP_PLATFORM
#include "sdkconfig.h"
#endif

namespace minitrain::config {

namespace {

std::string readConfigString(const char *configValue, const char *envVariable, const char *name) {
    if (configValue != nullptr && configValue[0] != '\0') {
        return std::string(configValue);
    }
    if (const char *env = std::getenv(envVariable); env != nullptr) {
        return std::string(env);
    }
    throw std::runtime_error(std::string("Configuration value missing for ") + name);
}

std::string parseHostFromUri(const std::string &uri) {
    static const std::regex uriRegex(R"(^wss://([^/:]+).*$)");
    std::smatch match;
    if (std::regex_match(uri, match, uriRegex)) {
        return match[1];
    }
    throw std::runtime_error("Unable to infer host name from URI " + uri);
}

} // namespace

TlsCredentialConfig loadTlsCredentialConfig() {
    TlsCredentialConfig credentials{};
#ifdef CONFIG_MINITRAIN_WSS_URI
    credentials.uri = readConfigString(CONFIG_MINITRAIN_WSS_URI, "MINITRAIN_WSS_URI", "MINITRAIN_WSS_URI");
#else
    credentials.uri = readConfigString(nullptr, "MINITRAIN_WSS_URI", "MINITRAIN_WSS_URI");
#endif
#ifdef CONFIG_MINITRAIN_CA_CERT_PEM
    credentials.caCertificatePem = readConfigString(CONFIG_MINITRAIN_CA_CERT_PEM, "MINITRAIN_CA_CERT_PEM", "MINITRAIN_CA_CERT_PEM");
#else
    credentials.caCertificatePem = readConfigString(nullptr, "MINITRAIN_CA_CERT_PEM", "MINITRAIN_CA_CERT_PEM");
#endif
#ifdef CONFIG_MINITRAIN_CLIENT_CERT_PEM
    credentials.clientCertificatePem = readConfigString(CONFIG_MINITRAIN_CLIENT_CERT_PEM, "MINITRAIN_CLIENT_CERT_PEM", "MINITRAIN_CLIENT_CERT_PEM");
#else
    credentials.clientCertificatePem = readConfigString(nullptr, "MINITRAIN_CLIENT_CERT_PEM", "MINITRAIN_CLIENT_CERT_PEM");
#endif
#ifdef CONFIG_MINITRAIN_CLIENT_KEY_PEM
    credentials.clientPrivateKeyPem = readConfigString(CONFIG_MINITRAIN_CLIENT_KEY_PEM, "MINITRAIN_CLIENT_KEY_PEM", "MINITRAIN_CLIENT_KEY_PEM");
#else
    credentials.clientPrivateKeyPem = readConfigString(nullptr, "MINITRAIN_CLIENT_KEY_PEM", "MINITRAIN_CLIENT_KEY_PEM");
#endif
#ifdef CONFIG_MINITRAIN_EXPECTED_HOST
    credentials.expectedHost = readConfigString(CONFIG_MINITRAIN_EXPECTED_HOST, "MINITRAIN_EXPECTED_HOST", "MINITRAIN_EXPECTED_HOST");
#else
    credentials.expectedHost = readConfigString(nullptr, "MINITRAIN_EXPECTED_HOST", "MINITRAIN_EXPECTED_HOST");
#endif
#ifdef CONFIG_MINITRAIN_ENFORCE_HOST_VALIDATION
    credentials.enforceHostnameValidation = CONFIG_MINITRAIN_ENFORCE_HOST_VALIDATION;
#else
    credentials.enforceHostnameValidation = true;
#endif

    if (credentials.expectedHost.empty()) {
        credentials.expectedHost = parseHostFromUri(credentials.uri);
    }

    return credentials;
}

} // namespace minitrain::config
