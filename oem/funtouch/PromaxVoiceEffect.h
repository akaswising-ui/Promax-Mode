/*
 * Promax Funtouch OEM Audio Effects HAL adapter.
 *
 * This file is compiled inside the AOSP AIDL Effects HAL default tree.
 * It is not an Android application/JNI component.
 */
#pragma once

#include <memory>
#include <string>
#include <vector>

#include <aidl/android/hardware/audio/effect/DefaultExtension.h>
#include <aidl/android/hardware/audio/effect/Descriptor.h>
#include <aidl/android/hardware/audio/effect/VendorExtension.h>
#include <aidl/android/media/audio/common/AudioUuid.h>

#include "effect-impl/EffectImpl.h"
#include "PromaxVoiceDsp.h"

namespace aidl::android::hardware::audio::effect {

struct PromaxVoiceControlV1 {
    uint32_t version = 1;
    uint32_t enabled = 0;
    int32_t effect = 0;
    float pitchSemitones = 0.0f;
    float formantSemitones = 0.0f;
    float noiseGateDb = -80.0f;
};
static_assert(sizeof(PromaxVoiceControlV1) == 24, "Promax control packet ABI changed");

class PromaxVoiceEffectContext final : public EffectContext {
public:
    PromaxVoiceEffectContext(size_t statusDepth, const Parameter::Common& common)
        : EffectContext(statusDepth, common) {}

    bool setParams(const std::vector<uint8_t>& bytes);
    std::vector<uint8_t> getParams() const;
    const PromaxVoiceControlV1& control() const noexcept { return mControl; }
    promax::audio::PromaxVoiceDsp& dsp() noexcept { return mDsp; }

private:
    PromaxVoiceControlV1 mControl{};
    promax::audio::PromaxVoiceDsp mDsp{};
};

class PromaxVoiceEffect final : public EffectImpl {
public:
    static const std::string kEffectName;
    static const AudioUuid kTypeUuid;
    static const AudioUuid kImplementationUuid;
    static const Descriptor kDescriptor;

    PromaxVoiceEffect() = default;
    ~PromaxVoiceEffect() override;

    ndk::ScopedAStatus getDescriptor(Descriptor* result) override;
    ndk::ScopedAStatus setParameterSpecific(const Parameter::Specific& specific)
            REQUIRES(mImplMutex) override;
    ndk::ScopedAStatus getParameterSpecific(const Parameter::Id& id,
            Parameter::Specific* specific) REQUIRES(mImplMutex) override;

    std::shared_ptr<EffectContext> createContext(const Parameter::Common& common)
            REQUIRES(mImplMutex) override;
    RetCode releaseContext() REQUIRES(mImplMutex) override;
    std::string getEffectName() override { return kEffectName; }

    IEffect::Status effectProcessImpl(float* input, float* output, int samples)
            REQUIRES(mImplMutex) override;

private:
    std::shared_ptr<PromaxVoiceEffectContext> mContext GUARDED_BY(mImplMutex);
};

}  // namespace aidl::android::hardware::audio::effect
