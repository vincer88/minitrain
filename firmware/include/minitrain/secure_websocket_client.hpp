#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

#include "minitrain/esp_target_check.hpp"

namespace minitrain {

struct TlsCredentialConfig {
    std::string uri;
    std::string expectedHost;
    std::string caCertificatePem;
    std::string clientCertificatePem;
    std::string clientPrivateKeyPem;
    bool enforceHostnameValidation{true};
};

class SecureWebSocketClient {
  public:
    using MessageHandler = std::function<void(const std::string &)>;
    using EventHandler = std::function<void()>;

    explicit SecureWebSocketClient(TlsCredentialConfig config);
    ~SecureWebSocketClient();

    SecureWebSocketClient(const SecureWebSocketClient &) = delete;
    SecureWebSocketClient &operator=(const SecureWebSocketClient &) = delete;

    SecureWebSocketClient(SecureWebSocketClient &&) noexcept;
    SecureWebSocketClient &operator=(SecureWebSocketClient &&) noexcept;

    void setMessageHandler(MessageHandler handler);
    void setOnConnected(EventHandler handler);
    void setOnDisconnected(EventHandler handler);

    bool connect();
    void close();
    bool isConnected() const;
    bool sendText(const std::string &payload);
    bool sendBinary(const std::uint8_t *payload, std::size_t length);

    const TlsCredentialConfig &config() const { return config_; }

  private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    TlsCredentialConfig config_;
};

} // namespace minitrain
