/*
 * Promax Mode Funtouch OEM Audio Effects HAL plugin.
 *
 * The effect is deliberately disabled by default. The privileged Funtouch
 * Game Assistant/controller must send a validated version-1 vendorEffect
 * packet only while a supported game voice session is active.
 */
#include "PromaxVoiceEffect.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <optional>

#include <aidl/android/hardware/audio/effect/DefaultExtension.h>
#include <aidl/android/hardware/audio/effect/Flags.h>
#include <android/binder_ibinder.h>
#include <Utils.h>

using aidl::android::hardware::audio::effect::DefaultExtension;
using aidl::android::hardware::audio::effect::Descriptor;
using aidl::android::hardware::audio::effect::IEffect;
using aidl::android::hardware::audio::effect::PromaxVoiceEffect;
using aidl::android::media::audio::common::AudioUuid;

namespace {
constexpr uint32_t kControlVersion = 1;
constexpr size_t kControlBytes = 24;

const AudioUuid kPromaxTypeUuid = {
    static_cast<int32_t>(0xb9e4e1d0), 0xa871, 0x4f11, 0x9b63,
    {0x2d, 0xb2, 0xd9, 0xb0, 0x84, 0x31}};
const AudioUuid kPromaxImplementationUuid = {
    static_cast<int32_t>(0x2c93b4f8), 0x10b2, 0x4d9f, 0xbf5d,
    {0x54, 0xa0, 0xb1, 0xf1, 0x4d, 0x5c}};
}  // namespace

namespace aidl::android::hardware::audio::effect {

const std::string PromaxVoiceEffect::kEffectName = "Promax Game Voice Changer";
const AudioUuid PromaxVoiceEffect::kTypeUuid = kPromaxTypeUuid;
const AudioUuid PromaxVoiceEffect::kImplementationUuid = kPromaxImplementationUuid;

const Descriptor PromaxVoiceEffect::kDescriptor = {
    .common = {
        .id = {
            .type = kPromaxTypeUuid,
            .uuid = kPromaxImplementationUuid,
            .proxy = std::nullopt,
        },
        .flags = {.type = Flags::Type::PRE_PROC},
        .cpuLoad = 25,
        .memoryUsage = 256,
        .name = PromaxVoiceEffect::kEffectName,
        .implementor = "Promax Mode / Funtouch OS OEM integration",
    },
    .capability = {},
};

bool PromaxVoiceEffectContext::setParams(const std::vector<uint8_t>& bytes) {
    if (bytes.size() != kControlBytes) return false;

    PromaxVoiceControlV1 next{};
    std::memcpy(&next, bytes.data(), sizeof(next));

    if (next.version != kControlVersion || next.enabled > 1 ||
        next.effect < 0 || next.effect > 9 ||
        !std::isfinite(next.pitchSemitones) ||
        !std::isfinite(next.formantSemitones) ||
        !std::isfinite(next.noiseGateDb)) {
        return false;
    }

    next.pitchSemitones = std::clamp(next.pitchSemitones, -12.0f, 12.0f);
    next.formantSemitones = std::clamp(next.formantSemitones, -8.0f, 8.0f);
    next.noiseGateDb = std::clamp(next.noiseGateDb, -80.0f, -20.0f);
    mControl = next;
    return true;
}

std::vector<uint8_t> PromaxVoiceEffectContext::getParams() const {
    const auto* begin = reinterpret_cast<const uint8_t*>(&mControl);
    return std::vector<uint8_t>(begin, begin + sizeof(mControl));
}

PromaxVoiceEffect::~PromaxVoiceEffect() {
    cleanUp();
}

ndk::ScopedAStatus PromaxVoiceEffect::getDescriptor(Descriptor* result) {
    if (result == nullptr) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
    }
    *result = kDescriptor;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PromaxVoiceEffect::setParameterSpecific(
        const Parameter::Specific& specific) {
    if (specific.getTag() != Parameter::Specific::vendorEffect || !mContext) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    const auto& vendorEffect = specific.get<Parameter::Specific::vendorEffect>();
    std::optional<DefaultExtension> extension;
    if (vendorEffect.extension.getParcelable(&extension) != STATUS_OK ||
        !extension.has_value() || !mContext->setParams(extension->bytes)) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus PromaxVoiceEffect::getParameterSpecific(
        const Parameter::Id& id, Parameter::Specific* specific) {
    if (specific == nullptr || !mContext ||
        id.getTag() != Parameter::Id::vendorEffectTag) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    const auto& vendorId = id.get<Parameter::Id::vendorEffectTag>();
    std::optional<DefaultExtension> requestedId;
    if (vendorId.extension.getParcelable(&requestedId) != STATUS_OK ||
        !requestedId.has_value()) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    DefaultExtension value;
    value.bytes = mContext->getParams();
    VendorExtension extension;
    if (extension.extension.setParcelable(value) != STATUS_OK) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    specific->set<Parameter::Specific::vendorEffect>(extension);
    return ndk::ScopedAStatus::ok();
}

std::shared_ptr<EffectContext> PromaxVoiceEffect::createContext(
        const Parameter::Common& common) {
    if (!mContext) {
        mContext = std::make_shared<PromaxVoiceEffectContext>(1 /* status FMQ depth */, common);
    }
    return mContext;
}

RetCode PromaxVoiceEffect::releaseContext() {
    mContext.reset();
    return RetCode::SUCCESS;
}

IEffect::Status PromaxVoiceEffect::effectProcessImpl(
        float* input, float* output, int samples) {
    if (input == nullptr || output == nullptr || samples <= 0) {
        return {STATUS_OK, 0, 0};
    }
    if (samples == 0) return {STATUS_OK, 0, 0};

    std::copy_n(input, samples, output);
    if (!mContext) return {STATUS_OK, samples, samples};

    const auto& control = mContext->control();
    if (control.enabled == 0) return {STATUS_OK, samples, samples};

    const auto common = mContext->getCommon();
    const auto inputChannels =
            ::aidl::android::hardware::audio::common::getChannelCount(
                    common.input.base.channelMask);
    const auto outputChannels =
            ::aidl::android::hardware::audio::common::getChannelCount(
                    common.output.base.channelMask);
    const int sampleRate = static_cast<int>(common.input.base.sampleRate);

    if (inputChannels == 0 || inputChannels > 2 ||
        inputChannels != outputChannels || sampleRate < 8000 ||
        samples % static_cast<int>(inputChannels) != 0) {
        return {STATUS_OK, samples, samples};
    }

    promax::audio::VoiceDspParameters parameters{};
    parameters.effect = control.effect;
    parameters.pitchSemitones = control.pitchSemitones;
    parameters.formantSemitones = control.formantSemitones;
    parameters.noiseGateDb = control.noiseGateDb;

    mContext->dsp().process(
            output,
            static_cast<size_t>(samples) / inputChannels,
            static_cast<int>(inputChannels),
            sampleRate,
            parameters);
    return {STATUS_OK, samples, samples};
}

}  // namespace aidl::android::hardware::audio::effect

extern "C" binder_exception_t createEffect(
        const AudioUuid* implementationUuid,
        std::shared_ptr<IEffect>* instance) {
    if (implementationUuid == nullptr || instance == nullptr ||
        *implementationUuid != PromaxVoiceEffect::kImplementationUuid) {
        return EX_ILLEGAL_ARGUMENT;
    }
    *instance = ndk::SharedRefBase::make<PromaxVoiceEffect>();
    return *instance ? EX_NONE : EX_TRANSACTION_FAILED;
}

extern "C" binder_exception_t queryEffect(
        const AudioUuid* implementationUuid, Descriptor* result) {
    if (implementationUuid == nullptr || result == nullptr ||
        *implementationUuid != PromaxVoiceEffect::kImplementationUuid) {
        return EX_ILLEGAL_ARGUMENT;
    }
    *result = PromaxVoiceEffect::kDescriptor;
    return EX_NONE;
}

extern "C" binder_exception_t destroyEffect(
        const std::shared_ptr<IEffect>& instance) {
    return instance ? EX_NONE : EX_ILLEGAL_ARGUMENT;
}
