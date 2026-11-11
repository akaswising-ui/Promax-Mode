#include "PromaxVoiceDsp.h"
#include <algorithm>
#include <cmath>
namespace promax::audio {
namespace { constexpr float PI=3.14159265358979323846f; float finite(float x){return std::isfinite(x)?x:0.0f;} }
void PromaxVoiceDsp::Biquad::peak(float hz,float db,float q,float rate) noexcept {
 hz=std::clamp(hz,80.0f,std::max(100.0f,rate*.45f)); q=std::max(.1f,q);
 float a=std::pow(10.0f,db/40),w=2*PI*hz/rate,al=std::sin(w)/(2*q),c=std::cos(w),a0=1+al/a;
 b0=(1+al*a)/a0;b1=-2*c/a0;b2=(1-al*a)/a0;a1=-2*c/a0;a2=(1-al/a)/a0;
}
float PromaxVoiceDsp::Biquad::run(float x) noexcept {float y=b0*x+z1;z1=b1*x-a1*y+z2;z2=b2*x-a2*y;return y;}
void PromaxVoiceDsp::reset() noexcept {ch={};rate_=0;formant_=999;}
float PromaxVoiceDsp::delay(const std::array<float,N>& b,std::size_t w,float d) noexcept {
 float p=float(w)-d;while(p<0)p+=float(N);while(p>=float(N))p-=float(N);
 auto i=std::size_t(p),j=(i+1)%N;float f=p-float(i);return b[i]*(1-f)+b[j]*f;
}
float PromaxVoiceDsp::one(float x,Channel& s,int rate,const VoiceDspParameters& p,float gate) noexcept {
 float y=x,pitch=std::clamp(finite(p.pitchSemitones),-12.f,12.f);
 if(std::abs(pitch)>=.05f) {
  float ratio=std::pow(2.f,pitch/12),grain=std::clamp(float(rate)*.04f,512.f,4096.f),base=std::clamp(float(rate)*.11f,1024.f,7000.f);
  s.pitch[s.pw]=y;float aP=s.phase,bP=(aP+.5f>=1)?aP-.5f:aP+.5f;
  float a=delay(s.pitch,s.pw,std::clamp(base+(1-ratio)*aP*grain,1.f,8000.f));
  float b=delay(s.pitch,s.pw,std::clamp(base+(1-ratio)*bP*grain,1.f,8000.f));
  float fade=aP<.5f?.5f-.5f*std::cos(aP*2*PI):.5f-.5f*std::cos((1-aP)*2*PI);
  y=a*fade+b*(1-fade);s.phase+=1/grain;if(s.phase>=1)s.phase-=1;s.pw=(s.pw+1)%N;
 }
 y=s.high.run(s.low.run(y));
 if(gate>0){s.env=std::max(std::abs(y),s.env*.995f);if(s.env<gate){float a=std::clamp(s.env/gate,0.f,1.f);y*=a*a;}}
 switch(p.effect){
 case 1:y*=std::sin(s.mod*2*PI)*.75f+.25f;break;
 case 2:y*=.55f+.45f*std::sin(s.mod*4*PI);break;
 case 3:y=std::tanh(y*3.5f);break;
 case 4:{float d=s.echo[s.ew];s.echo[s.ew]=y;s.ew=(s.ew+1)%N;y+=d*.42f;break;}
 case 7:s.lp+=.075f*(y-s.lp);y=s.lp*(.9f+.1f*std::sin(s.mod*2*PI));break;
 case 8:y*=.86f+.14f*std::sin(s.mod*8*PI);break;
 case 9:y=std::tanh(y*5);break; default:break;}
 s.mod+=80.f/float(rate);if(s.mod>=1)s.mod-=1;return std::clamp(finite(y),-1.f,1.f);
}
void PromaxVoiceDsp::process(float* d,std::size_t frames,int channels,int rate,const VoiceDspParameters& p) noexcept {
 if(!d||!frames||channels<=0||rate<8000)return;
 float fm=std::clamp(finite(p.formantSemitones),-8.f,8.f);
 if(rate_!=rate||std::abs(formant_-fm)>.001f){float r=std::pow(2.f,fm/12);for(auto& s:ch){s.low.peak(650*r,2.2f,3,rate);s.high.peak(1250*r,1.5f,3.5f,rate);}rate_=rate;formant_=fm;}
 float db=finite(p.noiseGateDb),gate=db<=-79.5f?0.f:std::pow(10.f,std::clamp(db,-80.f,-20.f)/20.f);
 for(std::size_t f=0;f<frames;++f)for(int c=0;c<channels;++c){auto i=f*std::size_t(channels)+std::size_t(c);d[i]=c<2?one(d[i],ch[std::size_t(c)],rate,p,gate):std::clamp(finite(d[i]),-1.f,1.f);}
}
void PromaxVoiceDsp::processPcm16(std::int16_t* d,std::size_t n,int rate,const VoiceDspParameters& p) noexcept {
 if(!d||!n||rate<8000)return;
 float fm=std::clamp(finite(p.formantSemitones),-8.f,8.f);
 if(rate_!=rate||std::abs(formant_-fm)>.001f){float r=std::pow(2.f,fm/12);for(auto& s:ch){s.low.peak(650*r,2.2f,3,rate);s.high.peak(1250*r,1.5f,3.5f,rate);}rate_=rate;formant_=fm;}
 float db=finite(p.noiseGateDb),gate=db<=-79.5f?0.f:std::pow(10.f,std::clamp(db,-80.f,-20.f)/20.f);
 for(std::size_t i=0;i<n;++i){float x=float(d[i])/32768.f,y=one(x,ch[0],rate,p,gate);d[i]=std::int16_t(std::lrint(std::clamp(y*32768.f,-32768.f,32767.f)));}
}
}
