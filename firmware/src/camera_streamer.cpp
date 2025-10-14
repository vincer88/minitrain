#include "minitrain/camera_streamer.hpp"

#include <algorithm>

namespace minitrain {

CameraStreamer::Frame::Frame() = default;

CameraStreamer::Frame::Frame(camera_fb_t *frame, CameraStreamer *owner) : frame_(frame), owner_(owner) {}

CameraStreamer::Frame::Frame(Frame &&other) noexcept : frame_(other.frame_), owner_(other.owner_) {
    other.frame_ = nullptr;
    other.owner_ = nullptr;
}

CameraStreamer::Frame &CameraStreamer::Frame::operator=(Frame &&other) noexcept {
    if (this != &other) {
        release();
        frame_ = other.frame_;
        owner_ = other.owner_;
        other.frame_ = nullptr;
        other.owner_ = nullptr;
    }
    return *this;
}

CameraStreamer::Frame::~Frame() { release(); }

const std::uint8_t *CameraStreamer::Frame::data() const { return frame_ ? frame_->buf : nullptr; }

std::size_t CameraStreamer::Frame::size() const { return frame_ ? frame_->len : 0U; }

void CameraStreamer::Frame::release() {
    if (frame_ && owner_) {
        owner_->returnFrame(frame_);
    }
    frame_ = nullptr;
    owner_ = nullptr;
}

CameraStreamer::CameraStreamer() = default;

CameraStreamer::~CameraStreamer() { stop(); }

bool CameraStreamer::initialize(const camera_config_t &config,
                                std::chrono::milliseconds captureInterval,
                                std::size_t maxBufferedFrames,
                                std::size_t maxConsecutiveFailures,
                                ErrorHandler errorHandler) {
    stop();

    config_ = config;
    captureInterval_ = captureInterval;
    maxBufferedFrames_ = std::max<std::size_t>(1, maxBufferedFrames);
    maxConsecutiveFailures_ = std::max<std::size_t>(1, maxConsecutiveFailures);
    errorHandler_ = std::move(errorHandler);

#ifdef ESP_PLATFORM
    esp_err_t err = esp_camera_init(&config_);
    if (err != ESP_OK) {
        if (errorHandler_) {
            errorHandler_(std::string("Failed to initialise camera: ") + esp_err_to_name(err));
        }
        return false;
    }
#endif

    initialized_ = true;
    return true;
}

bool CameraStreamer::start() {
    if (!initialized_ || running_) {
        return running_;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        stopRequested_ = false;
        frameQueue_.clear();
    }

    running_ = true;
    captureThread_ = std::thread(&CameraStreamer::captureLoop, this);
    return true;
}

void CameraStreamer::stop() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stopRequested_ = true;
    }
    frameAvailable_.notify_all();

    if (captureThread_.joinable()) {
        captureThread_.join();
    }

    std::deque<camera_fb_t *> remainingFrames;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        remainingFrames.swap(frameQueue_);
        running_ = false;
    }

    for (auto *frame : remainingFrames) {
        returnFrame(frame);
    }

#ifdef ESP_PLATFORM
    if (initialized_) {
        esp_camera_deinit();
    }
#endif

    initialized_ = false;
    stopRequested_ = false;
}

std::optional<CameraStreamer::Frame> CameraStreamer::tryAcquireFrame(std::chrono::milliseconds timeout) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!running_) {
        return std::nullopt;
    }

    if (!frameAvailable_.wait_for(lock, timeout, [this]() { return !frameQueue_.empty() || !running_; })) {
        return std::nullopt;
    }

    if (!running_ || frameQueue_.empty()) {
        return std::nullopt;
    }

    auto *frame = frameQueue_.front();
    frameQueue_.pop_front();
    return Frame(frame, this);
}

camera_config_t CameraStreamer::createDefaultConfig() {
    camera_config_t config{};
#ifdef ESP_PLATFORM
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer = LEDC_TIMER_0;
    config.pin_d0 = 5;
    config.pin_d1 = 18;
    config.pin_d2 = 19;
    config.pin_d3 = 21;
    config.pin_d4 = 36;
    config.pin_d5 = 39;
    config.pin_d6 = 34;
    config.pin_d7 = 35;
    config.pin_xclk = 0;
    config.pin_pclk = 22;
    config.pin_vsync = 25;
    config.pin_href = 23;
    config.pin_sccb_sda = 26;
    config.pin_sccb_scl = 27;
    config.pin_pwdn = 32;
    config.pin_reset = -1;
    config.xclk_freq_hz = 20000000;
    config.pixel_format = PIXFORMAT_JPEG;
    config.frame_size = FRAMESIZE_VGA;
    config.jpeg_quality = 12;
    config.fb_count = 3;
    config.grab_mode = CAMERA_GRAB_LATEST;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.sccb_i2c_port = 0;
    config.clock_speed = 0;
#else
    config.pixel_format = PIXFORMAT_JPEG;
    config.frame_size = FRAMESIZE_QVGA;
    config.jpeg_quality = 20;
    config.fb_count = 2;
#endif
    return config;
}

void CameraStreamer::captureLoop() {
    std::size_t consecutiveFailures = 0;
    std::size_t overflowEvents = 0;

    while (true) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopRequested_) {
                break;
            }
        }

        camera_fb_t *frame = esp_camera_fb_get();
        if (frame == nullptr) {
            ++consecutiveFailures;
            if (consecutiveFailures >= maxConsecutiveFailures_) {
                if (errorHandler_) {
                    errorHandler_("Camera capture failed repeatedly; stopping stream");
                }
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    stopRequested_ = true;
                }
                break;
            }
            continue;
        }

        consecutiveFailures = 0;
        camera_fb_t *droppedFrame = nullptr;
        bool overflowed = false;

        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stopRequested_) {
                droppedFrame = frame;
                frame = nullptr;
            } else {
                if (frameQueue_.size() >= maxBufferedFrames_) {
                    droppedFrame = frameQueue_.front();
                    frameQueue_.pop_front();
                    overflowed = true;
                }
                frameQueue_.push_back(frame);
                frame = nullptr;
            }
        }

        if (droppedFrame != nullptr) {
            returnFrame(droppedFrame);
        }

        if (overflowed) {
            if (errorHandler_) {
                errorHandler_("Camera frame queue overflow; dropping oldest frame");
            }
            ++overflowEvents;
            if (overflowEvents >= maxConsecutiveFailures_) {
                if (errorHandler_) {
                    errorHandler_("Camera overwhelmed; stopping stream");
                }
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    stopRequested_ = true;
                }
                break;
            }
        } else {
            overflowEvents = 0;
        }

        frameAvailable_.notify_one();

        if (captureInterval_ > std::chrono::milliseconds::zero()) {
            std::this_thread::sleep_for(captureInterval_);
        }
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        running_ = false;
    }
    frameAvailable_.notify_all();
}

void CameraStreamer::returnFrame(camera_fb_t *frame) {
    if (frame == nullptr) {
        return;
    }
#ifdef ESP_PLATFORM
    esp_camera_fb_return(frame);
#else
    (void)frame;
#endif
}

} // namespace minitrain

