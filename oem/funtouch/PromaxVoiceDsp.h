#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
namespace promax::audio {
struct VoiceDspParameters { int effect=0; float pitchSemitones=0, formantSemitones=0, noiseGateDb=-80; };
class PromaxVoiceDsp final {
public:
 void reset() noexcept;
 void process(float* data,std::size_t frames,int channels,int sampleRate,const VoiceDspParameters& p) noexcept;
 void processPcm16(std::int16_t* mono,std::size_t count,int sampleRate,const VoiceDspParameters& p) noexcept;
private:
 static constexpr std::size_t N=16384;
 struct Biquad { float b0=1,b1=0,b2=0,a1=0,a2=0,z1=0,z2=0; void peak(float hz,float db,float q,float rate) noexcept; float run(float x) noexcept; };
 struct Channel { std::array<float,N> pitch{},echo{}; std::size_t pw=0,ew=0; float phase=0,lp=0,env=0,mod=0; Biquad low,high; };
 static float delay(const std::array<float,N>& b,std::size_t w,float d) noexcept;
 float one(float x,Channel& s,int rate,const VoiceDspParameters& p,float gate) noexcept;
 std::array<Channel,2> ch{}; int rate_=0; float formant_=999;
};
}
