#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

extern "C" {
#include "layer3.h"
}

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr int kSampleRate = 44100;
constexpr int kDelaySize = 16384;
std::array<float, kDelaySize> g_pitchDelay{};
std::array<float, kDelaySize> g_echoDelay{};
int g_pitchWrite = 0;
int g_echoWrite = 0;
float g_pitchPhase = 0.0f;
float g_lowPassState = 0.0f;
float g_envelope = 0.0f;
float g_modulationPhase = 0.0f;

struct Biquad {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float z1 = 0.0f, z2 = 0.0f;

    void configurePeak(float frequency, float gainDb, float q) {
        const float f = std::clamp(frequency, 80.0f, 7000.0f);
        const float a = std::pow(10.0f, gainDb / 40.0f);
        const float w0 = 2.0f * kPi * f / static_cast<float>(kSampleRate);
        const float alpha = std::sin(w0) / (2.0f * q);
        const float cosw = std::cos(w0);
        const float a0 = 1.0f + alpha / a;
        b0 = (1.0f + alpha * a) / a0;
        b1 = (-2.0f * cosw) / a0;
        b2 = (1.0f - alpha * a) / a0;
        a1 = (-2.0f * cosw) / a0;
        a2 = (1.0f - alpha / a) / a0;
    }

    float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
};

// DSP state persists across AudioRecord chunks.
Biquad g_formantLow;
Biquad g_formantHigh;

float readDelay(const std::array<float, kDelaySize>& buffer, int writeIndex, float delay) {
    float position = static_cast<float>(writeIndex) - delay;
    while (position < 0.0f) position += static_cast<float>(kDelaySize);
    while (position >= static_cast<float>(kDelaySize)) position -= static_cast<float>(kDelaySize);
    const int i0 = static_cast<int>(position);
    const int i1 = (i0 + 1) % kDelaySize;
    const float fraction = position - static_cast<float>(i0);
    return buffer[i0] * (1.0f - fraction) + buffer[i1] * fraction;
}

float applyPitch(float input, float semitones) {
    if (std::abs(semitones) < 0.05f) return input;
    const float ratio = std::pow(2.0f, std::clamp(semitones, -12.0f, 12.0f) / 12.0f);
    constexpr float grain = 1764.0f;
    constexpr float baseDelay = 5000.0f;
    g_pitchDelay[g_pitchWrite] = input;

    const float phaseA = g_pitchPhase;
    float phaseB = phaseA + 0.5f;
    if (phaseB >= 1.0f) phaseB -= 1.0f;

    const float delayA = baseDelay + (1.0f - ratio) * phaseA * grain;
    const float delayB = baseDelay + (1.0f - ratio) * phaseB * grain;
    const float a = readDelay(g_pitchDelay, g_pitchWrite, delayA);
    const float b = readDelay(g_pitchDelay, g_pitchWrite, delayB);
    const float fade = phaseA < 0.5f
        ? 0.5f - 0.5f * std::cos(phaseA * 2.0f * kPi)
        : 0.5f - 0.5f * std::cos((1.0f - phaseA) * 2.0f * kPi);
    const float output = a * fade + b * (1.0f - fade);

    g_pitchPhase += 1.0f / grain;
    if (g_pitchPhase >= 1.0f) g_pitchPhase -= 1.0f;
    g_pitchWrite = (g_pitchWrite + 1) % kDelaySize;
    return output;
}

float applyNoiseGate(float input, float threshold) {
    const float magnitude = std::abs(input);
    g_envelope = std::max(magnitude, g_envelope * 0.995f);
    if (threshold <= 1.0f) return input;
    if (g_envelope < threshold) {
        const float attenuation = std::clamp(g_envelope / threshold, 0.0f, 1.0f);
        return input * attenuation * attenuation;
    }
    return input;
}

std::string fromJString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

int readLe16(const unsigned char* p) {
    return static_cast<int>(p[0]) | (static_cast<int>(p[1]) << 8);
}
int readLe32(const unsigned char* p) {
    return static_cast<int>(p[0]) |
           (static_cast<int>(p[1]) << 8) |
           (static_cast<int>(p[2]) << 16) |
           (static_cast<int>(p[3]) << 24);
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_promax_mode_audio_NativeDsp_process(
    JNIEnv* env, jobject, jshortArray samples,
    jint count, jint effect, jfloat pitchSemitones,
    jfloat formantSemitones, jfloat noiseGateDb) {
    if (samples == nullptr || count <= 0) return;
    const jsize length = env->GetArrayLength(samples);
    const jint safeCount = std::min<jint>(count, length);
    jshort* data = env->GetShortArrayElements(samples, nullptr);
    if (data == nullptr) return;

    const float threshold = noiseGateDb <= -79.5f
        ? 0.0f
        : std::pow(10.0f, std::clamp(noiseGateDb, -80.0f, -20.0f) / 20.0f) * 32768.0f;
    const float formantRatio = std::pow(2.0f, std::clamp(formantSemitones, -8.0f, 8.0f) / 12.0f);
    g_formantLow.configurePeak(650.0f * formantRatio, 2.2f, 3.0f);
    g_formantHigh.configurePeak(1250.0f * formantRatio, 1.5f, 3.5f);

    for (jint i = 0; i < safeCount; ++i) {
        float sample = static_cast<float>(data[i]);
        sample = applyPitch(sample, pitchSemitones);
        sample = g_formantHigh.process(g_formantLow.process(sample));
        sample = applyNoiseGate(sample, threshold);

        switch (effect) {
            case 1: // Robot
                sample *= std::sin(g_modulationPhase * 2.0f * kPi) * 0.75f + 0.25f;
                break;
            case 2: // Alien
                sample *= 0.55f + 0.45f * std::sin(g_modulationPhase * 4.0f * kPi);
                break;
            case 3: // Monster
                sample = std::tanh(sample / 7000.0f) * 25000.0f;
                break;
            case 4: { // Echo
                const float delayed = g_echoDelay[g_echoWrite];
                g_echoDelay[g_echoWrite] = sample;
                g_echoWrite = (g_echoWrite + 1) % kDelaySize;
                sample += delayed * 0.42f;
                break;
            }
            case 7: // Underwater
                g_lowPassState += 0.075f * (sample - g_lowPassState);
                sample = g_lowPassState * (0.9f + 0.1f * std::sin(g_modulationPhase * 2.0f * kPi));
                break;
            case 8: // Helium: preset pitch/formant lift plus a light cartoon vibrato
                sample *= 0.86f + 0.14f * std::sin(g_modulationPhase * 8.0f * kPi);
                break;
            case 9: // Demon: deeper preset pitch/formant with stronger saturation
                sample = std::tanh(sample / 4500.0f) * 28000.0f;
                break;
            default:
                break;
        }

        g_modulationPhase += 80.0f / static_cast<float>(kSampleRate);
        if (g_modulationPhase >= 1.0f) g_modulationPhase -= 1.0f;
        data[i] = static_cast<jshort>(std::clamp(sample, -32768.0f, 32767.0f));
    }
    env->ReleaseShortArrayElements(samples, data, 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_promax_mode_audio_NativeMp3_encodeWavToMp3(
    JNIEnv* env, jobject, jstring inputPath, jstring outputPath) {
    const std::string input = fromJString(env, inputPath);
    const std::string output = fromJString(env, outputPath);
    if (input.empty() || output.empty()) return -1;

    std::ifstream in(input, std::ios::binary);
    if (!in) return -2;
    unsigned char header[44]{};
    in.read(reinterpret_cast<char*>(header), sizeof(header));
    if (in.gcount() != sizeof(header) ||
        std::memcmp(header, "RIFF", 4) != 0 ||
        std::memcmp(header + 8, "WAVE", 4) != 0) return -3;

    const int channels = readLe16(header + 22);
    const int sampleRate = readLe32(header + 24);
    const int bitsPerSample = readLe16(header + 34);
    if (channels != 1 || sampleRate != 44100 || bitsPerSample != 16) return -4;

    std::ofstream out(output, std::ios::binary | std::ios::trunc);
    if (!out) return -5;

    shine_config_t config{};
    config.wave.channels = PCM_MONO;
    config.wave.samplerate = sampleRate;
    shine_set_config_mpeg_defaults(&config.mpeg);
    config.mpeg.mode = MONO;
    config.mpeg.bitr = 128;
    if (shine_check_config(sampleRate, config.mpeg.bitr) < 0) return -6;

    shine_t encoder = shine_initialise(&config);
    if (encoder == nullptr) return -7;

    const int samplesPerPass = shine_samples_per_pass(encoder);
    std::vector<int16_t> pcm(static_cast<size_t>(samplesPerPass), 0);
    std::vector<unsigned char> raw(static_cast<size_t>(samplesPerPass) * 2);
    int written = 0;
    int result = 0;

    while (in.read(reinterpret_cast<char*>(raw.data()), static_cast<std::streamsize>(raw.size())) ||
           in.gcount() > 0) {
        const std::streamsize bytesRead = in.gcount();
        const size_t samplesRead = static_cast<size_t>(bytesRead / 2);
        std::fill(pcm.begin(), pcm.end(), 0);
        for (size_t i = 0; i < samplesRead; ++i) {
            pcm[i] = static_cast<int16_t>(static_cast<uint16_t>(raw[i * 2]) |
                                         (static_cast<uint16_t>(raw[i * 2 + 1]) << 8));
        }
        unsigned char* encoded = shine_encode_buffer_interleaved(encoder, pcm.data(), &written);
        if (encoded != nullptr && written > 0) {
            out.write(reinterpret_cast<const char*>(encoded), written);
            if (!out) { result = -8; break; }
        }
        if (samplesRead < static_cast<size_t>(samplesPerPass)) break;
    }

    if (result == 0) {
        unsigned char* tail = shine_flush(encoder, &written);
        if (tail != nullptr && written > 0) {
            out.write(reinterpret_cast<const char*>(tail), written);
            if (!out) result = -9;
        }
    }
    shine_close(encoder);
    out.close();
    if (result != 0) std::remove(output.c_str());
    return result;
}
