#include <jni.h>
#include "PromaxVoiceDsp.h"
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
    JNIEnv* env, jobject, jshortArray samples, jint count, jint effect,
    jfloat pitchSemitones, jfloat formantSemitones, jfloat noiseGateDb) {
    if (samples == nullptr || count <= 0) return;
    const jint safeCount = std::min<jint>(count, env->GetArrayLength(samples));
    jshort* data = env->GetShortArrayElements(samples, nullptr);
    if (data == nullptr) return;
    static promax::audio::PromaxVoiceDsp dsp;
    const promax::audio::VoiceDspParameters params{effect,pitchSemitones,formantSemitones,noiseGateDb};
    dsp.processPcm16(reinterpret_cast<std::int16_t*>(data),static_cast<std::size_t>(safeCount),44100,params);
    env->ReleaseShortArrayElements(samples,data,0);
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
