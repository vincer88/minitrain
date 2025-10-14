#pragma once

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

#ifdef ESP_PLATFORM
#include "esp_camera.h"
#include "esp_err.h"
#include "esp_timer.h"
#else
using esp_err_t = int;
constexpr esp_err_t ESP_OK = 0;

using pixformat_t = int;
using framesize_t = int;
constexpr pixformat_t PIXFORMAT_JPEG = 0;
constexpr pixformat_t PIXFORMAT_YUV422 = 1;
constexpr framesize_t FRAMESIZE_VGA = 0;
constexpr framesize_t FRAMESIZE_QVGA = 1;

struct camera_config_t {
    int ledc_channel{0};
    int ledc_timer{0};
    int pin_pwdn{0};
    int pin_reset{0};
    int pin_xclk{0};
    int pin_sccb_sda{0};
    int pin_sccb_scl{0};
    int pin_d7{0};
    int pin_d6{0};
    int pin_d5{0};
    int pin_d4{0};
    int pin_d3{0};
    int pin_d2{0};
    int pin_d1{0};
    int pin_d0{0};
    int pin_vsync{0};
    int pin_href{0};
    int pin_pclk{0};
    int xclk_freq_hz{0};
    pixformat_t pixel_format{PIXFORMAT_JPEG};
    framesize_t frame_size{FRAMESIZE_VGA};
    int jpeg_quality{10};
    int fb_count{2};
    int grab_mode{0};
    int fb_location{0};
    bool dual_fb{false};
    int sccb_i2c_port{0};
    int clock_speed{0};
};

struct camera_fb_t {
    std::uint8_t *buf{nullptr};
    std::size_t len{0};
    std::size_t width{0};
    std::size_t height{0};
    pixformat_t format{PIXFORMAT_JPEG};
};

inline esp_err_t esp_camera_init(const camera_config_t *) { return ESP_OK; }
inline void esp_camera_deinit() {}
inline camera_fb_t *esp_camera_fb_get() { return nullptr; }
inline void esp_camera_fb_return(camera_fb_t *) {}
inline const char *esp_err_to_name(esp_err_t err) { return err == ESP_OK ? "ESP_OK" : "ESP_FAIL"; }

constexpr int CAMERA_GRAB_WHEN_EMPTY = 0;
constexpr int CAMERA_GRAB_LATEST = 1;
constexpr int CAMERA_FB_IN_PSRAM = 0;
constexpr int LEDC_TIMER_0 = 0;
constexpr int LEDC_CHANNEL_0 = 0;
#endif

namespace minitrain {

class CameraStreamer {
  public:
    class Frame {
      public:
        Frame();
        Frame(const Frame &) = delete;
        Frame &operator=(const Frame &) = delete;
        Frame(Frame &&other) noexcept;
        Frame &operator=(Frame &&other) noexcept;
        ~Frame();

        [[nodiscard]] const std::uint8_t *data() const;
        [[nodiscard]] std::size_t size() const;
        [[nodiscard]] camera_fb_t *raw() const { return frame_; }

      private:
        Frame(camera_fb_t *frame, CameraStreamer *owner);
        void release();

        camera_fb_t *frame_{nullptr};
        CameraStreamer *owner_{nullptr};

        friend class CameraStreamer;
    };

    using ErrorHandler = std::function<void(const std::string &)>;

    CameraStreamer();
    ~CameraStreamer();

    CameraStreamer(const CameraStreamer &) = delete;
    CameraStreamer &operator=(const CameraStreamer &) = delete;
    CameraStreamer(CameraStreamer &&) = delete;
    CameraStreamer &operator=(CameraStreamer &&) = delete;

    bool initialize(const camera_config_t &config,
                    std::chrono::milliseconds captureInterval = std::chrono::milliseconds{0},
                    std::size_t maxBufferedFrames = 2,
                    std::size_t maxConsecutiveFailures = 5,
                    ErrorHandler errorHandler = nullptr);

    bool start();
    void stop();

    [[nodiscard]] bool isInitialized() const { return initialized_; }
    [[nodiscard]] bool isRunning() const { return running_; }

    [[nodiscard]] std::optional<Frame> tryAcquireFrame(std::chrono::milliseconds timeout);

    static camera_config_t createDefaultConfig();

  private:
    void captureLoop();
    void returnFrame(camera_fb_t *frame);

    camera_config_t config_{};
    std::chrono::milliseconds captureInterval_{0};
    std::size_t maxBufferedFrames_{2};
    std::size_t maxConsecutiveFailures_{5};
    ErrorHandler errorHandler_{};

    bool initialized_{false};
    bool running_{false};
    bool stopRequested_{false};

    std::thread captureThread_;
    std::mutex mutex_;
    std::condition_variable frameAvailable_;
    std::deque<camera_fb_t *> frameQueue_;
};

} // namespace minitrain

