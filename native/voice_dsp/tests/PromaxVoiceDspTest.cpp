#include "PromaxVoiceDsp.h"
#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>
#include <iostream>
int main(){
 constexpr int rate=48000;constexpr std::size_t n=9600;std::vector<float> a(n);
 for(std::size_t i=0;i<n;++i)a[i]=.2f*std::sin(2*3.14159265f*220*float(i)/rate);
 promax::audio::PromaxVoiceDsp d;promax::audio::VoiceDspParameters p;p.effect=3;p.pitchSemitones=-5;p.formantSemitones=-2;
 d.process(a.data(),n,1,rate,p);bool nonzero=false;for(float x:a){assert(std::isfinite(x)&&std::abs(x)<=1);if(std::abs(x)>.0001f)nonzero=true;}assert(nonzero);
 std::vector<float> stereo(n*2,.1f);promax::audio::PromaxVoiceDsp sd;p.effect=1;p.pitchSemitones=0;sd.process(stereo.data(),n,2,44100,p);
 for(float x:stereo)assert(std::isfinite(x)&&std::abs(x)<=1);
 std::vector<std::int16_t> pcm(1024,8000);promax::audio::PromaxVoiceDsp pd;p.effect=2;pd.processPcm16(pcm.data(),pcm.size(),48000,p);
 bool changed=false;for(auto x:pcm)if(x!=8000)changed=true;assert(changed);
 std::cout<<"Promax DSP tests passed: PCM16/float, mono/stereo, 44.1/48 kHz.\n";
}
