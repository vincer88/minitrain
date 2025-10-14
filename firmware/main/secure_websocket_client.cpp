#include "minitrain/secure_websocket_client.hpp"

#include <cstdint>
#include <mutex>
#include <stdexcept>
#include <utility>

#ifdef ESP_PLATFORM
#include "esp_event.h"
#include "esp_log.h"
#include "esp_tls.h"
#include "esp_websocket_client.h"
#endif

namespace minitrain {

namespace {
constexpr const char *kLogTag = "mt_secure_ws";
}

struct SecureWebSocketClient::Impl {
#ifdef ESP_PLATFORM
    esp_websocket_client_handle_t client{nullptr};
    std::mutex mutex;
    MessageHandler messageHandler;
    EventHandler onConnected;
    EventHandler onDisconnected;

    static void handleEvent(void *handlerArg, esp_event_base_t base, int32_t eventId, void *eventData) {
        auto *self = static_cast<SecureWebSocketClient::Impl *>(handlerArg);
        if (base != WEBSOCKET_EVENTS) {
            return;
        }
        switch (eventId) {
        case WEBSOCKET_EVENT_CONNECTED: {
            std::lock_guard<std::mutex> lock(self->mutex);
            if (self->onConnected) {
                self->onConnected();
            }
            break;
        }
        case WEBSOCKET_EVENT_DISCONNECTED: {
            std::lock_guard<std::mutex> lock(self->mutex);
            if (self->onDisconnected) {
                self->onDisconnected();
            }
            break;
        }
        case WEBSOCKET_EVENT_DATA: {
            auto *data = static_cast<esp_websocket_event_data_t *>(eventData);
            if (data->op_code == WS_TRANSPORT_OPCODES_TEXT && data->data_len > 0) {
                std::string message(static_cast<const char *>(data->data_ptr), data->data_len);
                std::lock_guard<std::mutex> lock(self->mutex);
                if (self->messageHandler) {
                    self->messageHandler(message);
                }
            }
            break;
        }
        default:
            break;
        }
    }
#else
    bool connected{false};
    MessageHandler messageHandler;
    EventHandler onConnected;
    EventHandler onDisconnected;
#endif
};

SecureWebSocketClient::SecureWebSocketClient(TlsCredentialConfig config) : impl_(std::make_unique<Impl>()), config_(std::move(config)) {
}

SecureWebSocketClient::~SecureWebSocketClient() {
    close();
}

SecureWebSocketClient::SecureWebSocketClient(SecureWebSocketClient &&other) noexcept
    : impl_(std::move(other.impl_)), config_(std::move(other.config_)) {
}

SecureWebSocketClient &SecureWebSocketClient::operator=(SecureWebSocketClient &&other) noexcept {
    if (this != &other) {
        close();
        impl_ = std::move(other.impl_);
        config_ = std::move(other.config_);
    }
    return *this;
}

void SecureWebSocketClient::setMessageHandler(MessageHandler handler) {
    impl_->messageHandler = std::move(handler);
}

void SecureWebSocketClient::setOnConnected(EventHandler handler) {
    impl_->onConnected = std::move(handler);
}

void SecureWebSocketClient::setOnDisconnected(EventHandler handler) {
    impl_->onDisconnected = std::move(handler);
}

bool SecureWebSocketClient::connect() {
#ifdef ESP_PLATFORM
    if (impl_->client != nullptr) {
        return true;
    }

    esp_websocket_client_config_t wsConfig = {};
    wsConfig.uri = config_.uri.c_str();
    wsConfig.transport = WEBSOCKET_TRANSPORT_OVER_SSL;
    wsConfig.use_global_ca_store = false;
    wsConfig.skip_cert_common_name_check = !config_.enforceHostnameValidation;
    wsConfig.cert_pem = config_.caCertificatePem.c_str();
    wsConfig.client_cert_pem = config_.clientCertificatePem.empty() ? nullptr : config_.clientCertificatePem.c_str();
    wsConfig.client_key_pem = config_.clientPrivateKeyPem.empty() ? nullptr : config_.clientPrivateKeyPem.c_str();
    wsConfig.common_name = config_.expectedHost.c_str();
    wsConfig.subprotocol = nullptr;

    impl_->client = esp_websocket_client_init(&wsConfig);
    if (!impl_->client) {
#ifdef ESP_LOGE
        ESP_LOGE(kLogTag, "Failed to initialise websocket client");
#endif
        return false;
    }

    esp_websocket_register_events(impl_->client, WEBSOCKET_EVENT_ANY, &Impl::handleEvent, impl_.get());

    const esp_err_t err = esp_websocket_client_start(impl_->client);
    if (err != ESP_OK) {
#ifdef ESP_LOGE
        ESP_LOGE(kLogTag, "Failed to connect websocket: %s", esp_err_to_name(err));
#endif
        esp_websocket_client_destroy(impl_->client);
        impl_->client = nullptr;
        return false;
    }
    return true;
#else
    impl_->connected = true;
    if (impl_->onConnected) {
        impl_->onConnected();
    }
    return true;
#endif
}

void SecureWebSocketClient::close() {
#ifdef ESP_PLATFORM
    if (impl_ && impl_->client) {
        esp_websocket_client_stop(impl_->client);
        esp_websocket_client_destroy(impl_->client);
        impl_->client = nullptr;
    }
#else
    if (impl_ && impl_->connected) {
        impl_->connected = false;
        if (impl_->onDisconnected) {
            impl_->onDisconnected();
        }
    }
#endif
}

bool SecureWebSocketClient::isConnected() const {
#ifdef ESP_PLATFORM
    return impl_ && impl_->client && esp_websocket_client_is_connected(impl_->client);
#else
    return impl_ && impl_->connected;
#endif
}

bool SecureWebSocketClient::sendText(const std::string &payload) {
#ifdef ESP_PLATFORM
    if (!impl_ || !impl_->client || !esp_websocket_client_is_connected(impl_->client)) {
        return false;
    }
    const int result = esp_websocket_client_send_text(impl_->client, payload.c_str(), static_cast<int>(payload.size()), 10000);
    return result >= 0;
#else
    if (!impl_->connected) {
        return false;
    }
    if (impl_->messageHandler) {
        impl_->messageHandler(payload);
    }
    return true;
#endif
}

bool SecureWebSocketClient::sendBinary(const std::uint8_t *payload, std::size_t length) {
#ifdef ESP_PLATFORM
    if (!impl_ || !impl_->client || !esp_websocket_client_is_connected(impl_->client)) {
        return false;
    }
    const int result =
        esp_websocket_client_send_bin(impl_->client, reinterpret_cast<const char *>(payload), static_cast<int>(length), 10000);
    return result >= 0;
#else
    if (!impl_->connected) {
        return false;
    }
    (void)payload;
    (void)length;
    return true;
#endif
}

} // namespace minitrain
