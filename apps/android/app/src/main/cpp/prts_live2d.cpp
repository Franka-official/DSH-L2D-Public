// Cubism 5-r.4, official DrawModel chain into a transparent application FBO.
#include <jni.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>
#include "CubismFramework.hpp"
#include "Id/CubismIdManager.hpp"
#include "CubismModelSettingJson.hpp"
#include "Model/CubismUserModel.hpp"
#include "Model/CubismModel.hpp"
#include "Rendering/OpenGL/CubismRenderer_OpenGLES2.hpp"
#include "Motion/CubismMotion.hpp"
#include "Motion/CubismMotionJson.hpp"
#include "Motion/CubismExpressionMotion.hpp"
#include "Motion/CubismMotionManager.hpp"
#include "Motion/CubismExpressionMotionManager.hpp"
#include "Physics/CubismPhysics.hpp"
#include "Physics/CubismPhysicsJson.hpp"
#include "Effect/CubismEyeBlink.hpp"
#include "Effect/CubismBreath.hpp"
#include "Math/CubismMatrix44.hpp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,"PRTS-L2D",__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,"PRTS-L2D",__VA_ARGS__)
using namespace Live2D::Cubism::Framework;

class AppAllocator : public ICubismAllocator {
public:
    void* Allocate(csmSizeType size) override { return std::malloc(size); }
    void Deallocate(void* p) override { std::free(p); }
    void* AllocateAligned(csmSizeType size,csmUint32 alignment) override {
        void* p=nullptr; return posix_memalign(&p,std::max<csmUint32>(16,alignment),size)==0?p:nullptr;
    }
    void DeallocateAligned(void* p) override { std::free(p); }
};
static AppAllocator g_allocator;
static bool g_started=false;
static std::string g_shaderDir;
static csmByte* loadShader(const std::string path,csmSizeInt* size) {
    *size=0; const auto name=path.substr(path.find_last_of('/')+1);
    FILE* file=std::fopen((g_shaderDir+"/"+name).c_str(),"rb");
    if(!file) { LOGE("missing shader %s",name.c_str()); return nullptr; }
    std::fseek(file,0,SEEK_END); const long length=std::ftell(file); std::rewind(file);
    if(length<=0) { std::fclose(file); return nullptr; }
    auto* data=static_cast<csmByte*>(std::malloc(length));
    if(!data) { std::fclose(file); return nullptr; }
    const auto count=std::fread(data,1,length,file); std::fclose(file);
    if(count!=static_cast<size_t>(length)) { std::free(data); return nullptr; }
    *size=static_cast<csmSizeInt>(count); return data;
}
static void releaseShader(csmByte* p) { std::free(p); }
static std::string utf8(JNIEnv* env,jstring value) {
    if(!value) return {};
    const char* p=env->GetStringUTFChars(value,nullptr); if(!p) return {};
    std::string result(p); env->ReleaseStringUTFChars(value,p); return result;
}
static std::vector<csmByte> bytes(JNIEnv* env,jbyteArray value) {
    if(!value) return {};
    std::vector<csmByte> result(env->GetArrayLength(value));
    if(!result.empty()) env->GetByteArrayRegion(value,0,result.size(),reinterpret_cast<jbyte*>(result.data()));
    return result;
}
static constexpr std::array<const char*,6> callPoseParameters={
    "ParamAngleX","ParamAngleY","ParamAngleZ","ParamBodyAngleX","ParamBodyAngleY","ParamBodyAngleZ"
};
static constexpr std::array<const char*,6> whaleGestureInputs={
    "AngleX","AngleY","AngleZ","PositionX","LeanInput","PositionZ"
};
static constexpr std::array<const char*,5> armPoseParameters={"Movement2","Param62","Param59","Param60","Param61"};
static bool isCallGesture(const std::string& name) {
    static const std::set<std::string> names={"call:nod","call:shake","call:bow","call:sway",
        "call:nod-wide","call:shake-wide","call:bow-wide","call:sway-wide"};
    return names.count(name)>0;
}

class AppModel : public CubismUserModel {
public:
    CubismModelSettingJson* settings=nullptr;
    std::vector<GLuint> textures;
    std::map<std::string,std::vector<csmByte>> expressions,motions;
    std::vector<std::string> idleFiles;
    std::string hiddenPart;
    float mouth=0,dragX=0,dragY=0;
    int mouthIndex=-1;
    float mouthLogElapsed=0,mouthInputPeak=0,mouthAppliedPeak=0;
    bool mouthWasActive=false;
    size_t idleIndex=0;
    std::array<int,6> callPoseIndices{{-1,-1,-1,-1,-1,-1}};
    std::array<int,6> callInputIndices{{-1,-1,-1,-1,-1,-1}};
    std::array<float,6> callPosePeak{};
    std::array<float,6> callInputPeak{};
    bool whalePhysicsVerified=false,whaleInputGestures=false;
    std::string activeCallGesture;
    float callElapsed=0;
    unsigned callClampCount=0;
    CubismMotionManager armMotionManager;
    std::vector<csmByte> armUpMotion,armDownMotion;
    std::array<int,5> armIndices{{-1,-1,-1,-1,-1}};
    std::array<float,5> armCurrent{},armUpEnd{},armPeak{};
    int armState=0; // down / raising / holding / lowering; independent of idle and head gestures.
    float armHoldElapsed=0;
    bool armPairReady=false,lowerAfterRaise=false;
    // Reviewed facial inputs plus four content-verified cosmetic face effects.
    std::set<CubismIdHandle> physicsOutputIds;
    std::array<int,5> faceIndices{{-1,-1,-1,-1,-1}}; // mouth form, two brow heights, two blink apertures
    int eyeSmileIndex=-1;
    std::array<int,4> faceOverlayIndices{{-1,-1,-1,-1}}; // >< eyes, tears, dark face, blush; no props.
    int crossEyeIndex=-1;
    std::vector<int> normalEyePartIndices;
    std::vector<float> normalEyePartBaseline;
    bool crossEyePartsApplied=false;
    bool crossEyeStyleActive=false;
    float crossEyeLastLog=-1.f;
    std::array<float,5> emotionTarget{},emotionCurrent{},emotionLastLog{};
    bool continuousFaceReady=false,manualExpressionActive=false,faceObservationDue=false;
    bool appFaceRecipes=false;
    float manualFaceBlend=0,faceLogElapsed=0;
    float performanceTime=0,performanceSpeech=0,performanceLogElapsed=0;
    struct JsChannel { std::string id; int index; bool afterPhysics,face; float current,target; };
    std::vector<JsChannel> jsChannels;
    float jsFrameAge=1000.f,jsBlend=0.f,jsLogElapsed=0.f;
    unsigned long long jsFrames=0;
    std::vector<std::pair<int,int>> touchDrawables; // drawable index, reviewed head/body group
    std::array<float,3> presentationTarget{{1.f,0.f,0.f}},presentationCurrent{{1.f,0.f,0.f}};
    ~AppModel() override {
        _motionManager->StopAllMotions(); _expressionManager->StopAllMotions();
        finishCallGesture("disposed",true);
        resetArmPose("disposed");
        if(!textures.empty()) glDeleteTextures(textures.size(),textures.data());
        delete settings;
    }
    int parameter(const char* name) {
        const auto id=CubismFramework::GetIdManager()->GetId(name);
        for(int i=0;i<_model->GetParameterCount();++i) if(_model->GetParameterId(i)==id) return i;
        return -1; // Do not invent parameters that are absent from this model.
    }
    int part(const char* name) {
        const auto id=CubismFramework::GetIdManager()->GetId(name);
        for(int i=0;i<_model->GetPartCount();++i) if(_model->GetPartId(i)==id) return i;
        return -1; // GetPartIndex may manufacture an ID; only operate on real model parts.
    }
    void restoreCrossEyeParts() {
        if(!crossEyePartsApplied||!_model) return;
        for(size_t n=0;n<normalEyePartIndices.size();++n)
            _model->SetPartOpacity(normalEyePartIndices[n],normalEyePartBaseline[n]);
        crossEyePartsApplied=false;
    }
    bool configureCrossEyeParts(bool whale) {
        restoreCrossEyeParts();
        crossEyeIndex=-1; normalEyePartIndices.clear(); normalEyePartBaseline.clear(); crossEyeLastLog=-1.f;crossEyeStyleActive=false;
        if(!whale) return false;
        const int eye=parameter("key5"),left=part("Part9"),right=part("Part111"),face=part("Part99");
        const auto* parents=_model->GetPartParentPartIndices();
        if(eye<0||left<0||right<0||face<0||!parents||parents[left]!=face||parents[right]!=face) return false;
        const float lo=_model->GetParameterMinimumValue(eye),center=_model->GetParameterDefaultValue(eye),hi=_model->GetParameterMaximumValue(eye);
        if(!std::isfinite(lo)||!std::isfinite(center)||!std::isfinite(hi)||lo>0.f||std::abs(center)>.0001f||hi<1.f) return false;
        // Current moc/Core probe: normal-eye branches are siblings of the special-eye meshes,
        // not their parents. The CDI label "Part29" does not describe the rendered key5 meshes.
        const std::array<const char*,4> normalIds={"Part40","Part11","Part115","Part142"};
        const std::array<int,4> expectedParents={left,left,right,right};
        std::vector<int> indices;
        for(size_t n=0;n<normalIds.size();++n) {
            const int index=part(normalIds[n]);
            if(index<0||parents[index]!=expectedParents[n]) {
                LOGE("cross-eye exclusivity unavailable: normal branch hierarchy mismatch id=%s",normalIds[n]);
                return false;
            }
            indices.push_back(index);
        }
        const std::array<const char*,4> specialIds={"ArtMesh142","ArtMesh143","ArtMesh329","ArtMesh322"};
        for(const char* name:specialIds) {
            const auto id=CubismFramework::GetIdManager()->GetId(name);
            int parent=-1;
            for(int d=0;d<_model->GetDrawableCount();++d) if(_model->GetDrawableId(d)==id) {
                parent=_model->GetDrawableParentPartIndex(d); break;
            }
            if(parent!=left) {
                LOGE("cross-eye exclusivity unavailable: special mesh hierarchy mismatch id=%s",name);
                return false;
            }
        }
        crossEyeIndex=eye; normalEyePartIndices=std::move(indices);
        normalEyePartBaseline.resize(normalEyePartIndices.size());
        LOGI("cross-eye exclusivity configured: key5=%d normal_parts=Part40,Part11,Part115,Part142 special_mesh_parent=Part9 blink=retained gate=enter-.004-exit-.002",
            crossEyeIndex);
        return true;
    }
    void applyCrossEyeParts() {
        if(crossEyeIndex<0||normalEyePartIndices.empty()) return;
        const float value=_model->GetParameterValue(crossEyeIndex);
        if(!std::isfinite(value)) return;
        const float requested=std::clamp(value,0.f,1.f);
        crossEyeStyleActive=crossEyeStyleActive?requested>.002f:requested>=.004f;
        // The author's key5 is mesh opacity, not expression strength: fractional
        // key5 plus hidden normal eyes produces almost empty eyes (real Core grid).
        const float crossEye=crossEyeStyleActive?1.f:0.f;
        // Exponential fades can retain tiny positive values. Restore normal eyes and
        // explicitly remove the replacement style once it is below visible precision.
        _model->SetParameterValue(crossEyeIndex,crossEye);
        if(crossEyeStyleActive) {
            for(size_t n=0;n<normalEyePartIndices.size();++n) {
                const int index=normalEyePartIndices[n];
                const float baseline=_model->GetPartOpacity(index);
                normalEyePartBaseline[n]=baseline;
                // key5 is a replacement eye style, including low-intensity automatic emotion.
                // A linear (1-key5) blend leaves two eye styles visible indefinitely at .3 / .6.
                _model->SetPartOpacity(index,0.f);
            }
            crossEyePartsApplied=true;
        }
        if(std::abs(requested-crossEyeLastLog)>=.05f||(requested==0.f&&crossEyeLastLog!=0.f)||(requested==1.f&&crossEyeLastLog!=1.f)) {
            LOGI("cross-eye exclusivity applied: requested=%.4f key5=%.4f normal_eye_gain=%.1f normal_parts=%zu blink=retained restore=per-frame style=author-full",
                requested,crossEye,crossEyeStyleActive?0.f:1.f,normalEyePartIndices.size());
            crossEyeLastLog=requested;
        }
    }
    bool setup(const std::vector<csmByte>& moc,const std::vector<csmByte>& json,
               const std::vector<csmByte>& physics,const std::string& hidden) {
        if(moc.empty()||json.empty()) return false;
        LoadModel(moc.data(),moc.size()); if(!_model) return false;
        settings=new CubismModelSettingJson(json.data(),json.size()); hiddenPart=hidden;
        if(!physics.empty()) LoadPhysics(physics.data(),physics.size());
        _eyeBlink=CubismEyeBlink::Create(settings); _breath=CubismBreath::Create();
        csmVector<CubismBreath::BreathParameterData> breath;
        if(parameter("ParamBreath")>=0) breath.PushBack(CubismBreath::BreathParameterData(
            CubismFramework::GetIdManager()->GetId("ParamBreath"),.5f,.5f,3.5f,1.f));
        _breath->SetParameters(breath);
        std::set<std::vector<csmInt32>> maskSets;
        const auto* maskCounts=_model->GetDrawableMaskCounts();const auto* maskIndices=_model->GetDrawableMasks();
        for(int i=0;i<_model->GetDrawableCount();++i) {
            if(maskCounts&&maskCounts[i]>0) maskSets.insert(std::vector<csmInt32>(maskIndices[i],maskIndices[i]+maskCounts[i]));
        }
        // Official SDK packs 36 contexts into one buffer, or 32 per buffer in multi-buffer mode.
        const int maskBuffers=maskSets.size()<=36?1:static_cast<int>((maskSets.size()+31)/32);
        CreateRenderer(maskBuffers); auto* r=GetRenderer<Rendering::CubismRenderer_OpenGLES2>();
        LOGI("mask allocation uniqueClips=%zu buffers=%d",maskSets.size(),maskBuffers);
        if(!r) return false;
        r->SetExtShaderMode(false,false); r->IsPremultipliedAlpha(true);
        textures.assign(settings->GetTextureCount(),0); mouthIndex=parameter("ParamMouthOpenY");
        for(size_t n=0;n<callPoseParameters.size();++n) {
            const int i=callPoseIndices[n]=parameter(callPoseParameters[n]);
            if(i>=0) LOGI("gesture parameter range: id=%s min=%.4f default=%.4f max=%.4f",
                callPoseParameters[n],_model->GetParameterMinimumValue(i),
                _model->GetParameterDefaultValue(i),_model->GetParameterMaximumValue(i));
        }
        callInputIndices=callPoseIndices;
        for(const char* id:std::array<const char*,7>{"AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput"}) {
            const int i=parameter(id);
            if(i>=0) LOGI("gesture driver range: id=%s min=%.4f default=%.4f max=%.4f",id,
                _model->GetParameterMinimumValue(i),_model->GetParameterDefaultValue(i),_model->GetParameterMaximumValue(i));
        }
        if(!physics.empty()) {
            CubismPhysicsJson physicsJson(physics.data(),physics.size());
            for(int s=0;s<physicsJson.GetSubRigCount();++s)
                for(int j=0;j<physicsJson.GetOutputCount(s);++j)
                    physicsOutputIds.insert(physicsJson.GetOutputsDestinationId(s,j));
            whalePhysicsVerified=true;
            for(size_t n=0;n<callPoseParameters.size();++n) {
                const auto output=CubismFramework::GetIdManager()->GetId(callPoseParameters[n]);
                const auto input=CubismFramework::GetIdManager()->GetId(whaleGestureInputs[n]);
                bool matched=false;
                for(int s=0;s<physicsJson.GetSubRigCount();++s) {
                    bool hasDriver=false;
                    for(int j=0;j<physicsJson.GetInputCount(s);++j)
                        if(physicsJson.GetInputSourceId(s,j)==input&&physicsJson.GetInputWeight(s,j)>0)
                            hasDriver=true;
                    if(!hasDriver) continue;
                    for(int j=0;j<physicsJson.GetOutputCount(s);++j)
                        if(physicsJson.GetOutputsDestinationId(s,j)==output&&physicsJson.GetOutputWeight(s,j)>=99.99f)
                            matched=true;
                }
                whalePhysicsVerified=whalePhysicsVerified&&matched&&callPoseIndices[n]>=0&&parameter(whaleGestureInputs[n])>=0;
            }
        }
        if(mouthIndex>=0) LOGI("mouth range min=%.4f default=%.4f max=%.4f",
            _model->GetParameterMinimumValue(mouthIndex),_model->GetParameterDefaultValue(mouthIndex),
            _model->GetParameterMaximumValue(mouthIndex));
        LOGI("model canvas=%.4fx%.4f textures=%zu params=%d hiddenPart=%s",
            _model->GetCanvasWidth(),_model->GetCanvasHeight(),textures.size(),_model->GetParameterCount(),hiddenPart.c_str());
        _model->SaveParameters(); update(0); return true;
    }
    bool texture(int index,const csmByte* pixels,int width,int height) {
        if(index<0||index>=static_cast<int>(textures.size())||!pixels||width<=0||height<=0) return false;
        GLuint tex=0; glGenTextures(1,&tex); glBindTexture(GL_TEXTURE_2D,tex);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT,1);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        if(glGetError()!=GL_NO_ERROR) { glDeleteTextures(1,&tex); return false; }
        if(textures[index]) glDeleteTextures(1,&textures[index]); textures[index]=tex;
        GetRenderer<Rendering::CubismRenderer_OpenGLES2>()->BindTexture(index,tex);
        LOGI("texture index=%d uploaded %dx%d",index,width,height); return true;
    }
    bool play(const std::string& name,int priority) {
        auto it=motions.find(name);
        if(it==motions.end()) { LOGE("motion not registered: %s",name.c_str()); return false; }
        auto* motion=CubismMotion::Create(it->second.data(),it->second.size());
        if(!motion) { LOGE("motion playback failed: name=%s bytes=%zu phase=sdk-json",name.c_str(),it->second.size()); return false; }
        finishCallGesture("superseded",false);
        if(isCallGesture(name)) {
            activeCallGesture=name; callElapsed=0; callClampCount=0; callPosePeak.fill(0); callInputPeak.fill(0);
            const bool wide=name.size()>=5&&name.compare(name.size()-5,5,"-wide")==0;
            LOGI("gesture start: name=%s level=%s amplitude=%s duration=%.3f range=model-native rig=%s",
                name.c_str(),wide?"extended":"gentle",whaleInputGestures?"rig-span-profile":"original-direct",motion->GetDuration(),
                whaleInputGestures?"whale-physics-inputs":"direct");
        }
        _motionManager->StartMotionPriority(motion,true,priority); LOGI("motion started: %s",name.c_str()); return true;
    }
    void finishCallGesture(const char* reason,bool resetPose) {
        if(activeCallGesture.empty()) return;
        if(resetPose&&_model) {
            // Clear the motion's inputs; the author's physics continues to settle its outputs.
            for(int i:callInputIndices) if(i>=0)
                _model->SetParameterValue(i,_model->GetParameterDefaultValue(i));
            _model->SaveParameters();
        }
        LOGI("gesture finish: name=%s reason=%s layer=%s elapsed=%.3f pose_peak_abs=%.3f,%.3f,%.3f,%.3f,%.3f,%.3f input_peak_abs=%.3f,%.3f,%.3f,%.3f,%.3f,%.3f clamps=%u rig=%s",
            activeCallGesture.c_str(),reason,resetPose?"inputs-cleared-to-model-default":"replaced",callElapsed,
            callPosePeak[0],callPosePeak[1],callPosePeak[2],callPosePeak[3],callPosePeak[4],callPosePeak[5],
            callInputPeak[0],callInputPeak[1],callInputPeak[2],callInputPeak[3],callInputPeak[4],callInputPeak[5],callClampCount,
            whaleInputGestures?"whale-physics-inputs":"direct");
        activeCallGesture.clear();
    }
    std::string prepareCallGestureRig(bool useWhale) {
        callInputIndices=callPoseIndices; whaleInputGestures=false;
        if(!useWhale) {
            std::ostringstream result;result.precision(9);result<<"{";
            for(size_t n=0;n<callPoseIndices.size();++n) {
                const int i=callPoseIndices[n];if(i<0)return "";
                const float lo=_model->GetParameterMinimumValue(i),center=_model->GetParameterDefaultValue(i),hi=_model->GetParameterMaximumValue(i);
                if(!std::isfinite(lo)||!std::isfinite(center)||!std::isfinite(hi)||!(lo<=center&&center<=hi&&lo<hi))return "";
                if(n)result<<",";
                result<<"\""<<callPoseParameters[n]<<"\":{\"id\":\""<<callPoseParameters[n]<<"\",\"minimum\":"<<lo<<",\"default\":"<<center<<",\"maximum\":"<<hi
                    <<",\"sourceMinimum\":"<<lo<<",\"sourceDefault\":"<<center<<",\"sourceMaximum\":"<<hi<<"}";
            }
            result<<"}";return result.str();
        }
        if(!whalePhysicsVerified) { LOGE("Whale gesture inputs disabled: required physics links not verified"); return ""; }
        std::array<int,6> inputs;
        std::ostringstream result; result.precision(9); result<<"{";
        for(size_t n=0;n<inputs.size();++n) {
            const int source=callPoseIndices[n],target=inputs[n]=parameter(whaleGestureInputs[n]);
            if(source<0||target<0) return "";
            const float lo=_model->GetParameterMinimumValue(target),center=_model->GetParameterDefaultValue(target),hi=_model->GetParameterMaximumValue(target);
            const float sourceLo=_model->GetParameterMinimumValue(source),sourceCenter=_model->GetParameterDefaultValue(source),sourceHi=_model->GetParameterMaximumValue(source);
            LOGI("gesture input range: output=%s driver=%s min=%.4f default=%.4f max=%.4f",
                callPoseParameters[n],whaleGestureInputs[n],lo,center,hi);
            if(!std::isfinite(lo)||!std::isfinite(center)||!std::isfinite(hi)||!(lo<=center&&center<=hi&&lo<hi)||
               !std::isfinite(sourceLo)||!std::isfinite(sourceCenter)||!std::isfinite(sourceHi)||!(sourceLo<=sourceCenter&&sourceCenter<=sourceHi&&sourceLo<sourceHi)) {
                LOGE("gesture input range invalid: output=%s driver=%s",callPoseParameters[n],whaleGestureInputs[n]);
                return "";
            }
            if(n) result<<",";
            result<<"\""<<callPoseParameters[n]<<"\":{\"id\":\""<<whaleGestureInputs[n]
                <<"\",\"minimum\":"<<lo<<",\"default\":"<<center<<",\"maximum\":"<<hi
                <<",\"sourceMinimum\":"<<sourceLo<<",\"sourceDefault\":"<<sourceCenter<<",\"sourceMaximum\":"<<sourceHi<<"}";
        }
        result<<"}"; callInputIndices=inputs; whaleInputGestures=true;
        LOGI("gesture rig verified: whale-physics-inputs channels=6 output-overwrite=false");
        return result.str();
    }
    void clampCallInputs() {
        if(activeCallGesture.empty()) return;
        for(size_t n=0;n<callInputIndices.size();++n) {
            const int i=callInputIndices[n]; if(i<0) continue;
            const float value=_model->GetParameterValue(i);
            const float safe=std::isfinite(value)?std::clamp(value,
                _model->GetParameterMinimumValue(i),_model->GetParameterMaximumValue(i)):
                _model->GetParameterDefaultValue(i);
            if(!std::isfinite(value)||safe!=value) ++callClampCount;
            _model->SetParameterValue(i,safe);
            callInputPeak[n]=std::max(callInputPeak[n],std::abs(_model->GetParameterValue(i)));
        }
    }
    void observeCallPose() {
        if(activeCallGesture.empty()) return;
        for(size_t n=0;n<callPoseIndices.size();++n) {
            const int i=callPoseIndices[n];
            if(i>=0) callPosePeak[n]=std::max(callPosePeak[n],std::abs(_model->GetParameterValue(i)));
        }
    }
    bool expression(const std::string& name) {
        auto it=expressions.find(name);
        if(it==expressions.end()) { LOGE("expression not registered: %s",name.c_str()); return false; }
        auto* motion=CubismExpressionMotion::Create(it->second.data(),it->second.size()); if(!motion) return false;
        motion->SetFadeInTime(.45f); motion->SetFadeOutTime(.6f);
        _expressionManager->StartMotionPriority(motion,true,10); manualExpressionActive=true;
        LOGI("expression started: %s fade_in=0.450 fade_out=0.600",name.c_str()); return true;
    }
    bool configureContinuousFace(bool whale,int reviewedOverlayMask) {
        continuousFaceReady=false;
        appFaceRecipes=!whale;
        faceIndices.fill(-1); faceOverlayIndices.fill(-1);
        configureCrossEyeParts(whale);
        if(whale&&!whalePhysicsVerified) return false;
        // Ellen's unsuffixed BrowY parameters are physics inputs with no direct
        // geometry. This layer runs after physics, so bind the measured outputs.
        const std::array<const char*,5> names={"ParamMouthForm",whale?"ParamBrowLY":"ParamBrowLY2",
            whale?"ParamBrowRY":"ParamBrowRY2","ParamEyeLOpen","ParamEyeROpen"};
        int supported=0;
        for(size_t n=0;n<names.size();++n) {
            const int i=parameter(names[n]);
            if(i<0||(whale&&physicsOutputIds.count(CubismFramework::GetIdManager()->GetId(names[n])))) {
                LOGI("continuous face channel skipped: id=%s reason=%s",names[n],i<0?"absent":"physics-output");
                continue;
            }
            const float lo=_model->GetParameterMinimumValue(i),center=_model->GetParameterDefaultValue(i),hi=_model->GetParameterMaximumValue(i);
            if(!std::isfinite(lo)||!std::isfinite(center)||!std::isfinite(hi)||lo>center||center>hi||lo>=hi) continue;
            // Multiplicative blink modulation assumes the normal non-negative aperture rig.
            if(n>=3&&(lo<0||center<=0)) continue;
            faceIndices[n]=i; ++supported;
            LOGI("continuous face range: id=%s min=%.4f default=%.4f max=%.4f mode=%s",
                names[n],lo,center,hi,n>=3?"multiply-blink":"relative-shape");
        }
        continuousFaceReady=supported>0;
        eyeSmileIndex=parameter("EyeSmile");
        if(eyeSmileIndex>=0&&physicsOutputIds.count(CubismFramework::GetIdManager()->GetId("EyeSmile"))) eyeSmileIndex=-1;
        const std::array<const char*,4> effectIds={"key5","key2","key1","key7"};
        unsigned overlayMask=0;
        for(size_t n=0;whale&&n<effectIds.size();++n) {
            if(!(reviewedOverlayMask&(1<<n))) continue;
            const int index=parameter(effectIds[n]);
            if(index<0||physicsOutputIds.count(CubismFramework::GetIdManager()->GetId(effectIds[n]))) continue;
            const float lo=_model->GetParameterMinimumValue(index),center=_model->GetParameterDefaultValue(index),hi=_model->GetParameterMaximumValue(index);
            if(!std::isfinite(lo)||!std::isfinite(center)||!std::isfinite(hi)||lo>0||std::abs(center)>.0001f||hi<1) continue;
            faceOverlayIndices[n]=index; overlayMask|=1u<<n;
            LOGI("automatic face effect range: id=%s min=%.4f default=%.4f max=%.4f",effectIds[n],lo,center,hi);
        }
        LOGI("continuous face configured: supported=%d eye_smile=%s overlay_mask=%u mouth_open=tts-owned physics=retained blink=independent-idle",
            supported,eyeSmileIndex>=0?"input":"absent",overlayMask);
        if(appFaceRecipes&&continuousFaceReady) {
            // Build model-specific face recipes from real ranges, not prop/costume switches.
            const std::array<const char*,6> tags={"happy","sad","angry","surprised","thinking","shy"};
            const std::array<std::array<float,5>,6> shapes={{{.78f,.20f,.20f,.68f,.68f},
                {-.58f,.65f,.65f,.72f,.72f},{-.40f,-.78f,-.78f,.76f,.76f},
                {.24f,.85f,.85f,1.25f,1.25f},{-.12f,.15f,-.38f,.78f,.78f},
                {.58f,.30f,.30f,.72f,.72f}}};
            const auto& ids=names;
            for(size_t r=0;r<tags.size();++r) {
                std::ostringstream json; json.precision(9);
                json<<"{\"Type\":\"Live2D Expression\",\"Parameters\":["; bool comma=false;
                for(size_t n=0;n<faceIndices.size();++n) {
                    const int i=faceIndices[n]; if(i<0) continue;
                    const float lo=_model->GetParameterMinimumValue(i),center=_model->GetParameterDefaultValue(i),hi=_model->GetParameterMaximumValue(i);
                    const float shape=shapes[r][n];
                    const float value=n>=3?center*shape:center+shape*(shape>=0?hi-center:center-lo);
                    if(comma) json<<","; comma=true;
                    json<<"{\"Id\":\""<<ids[n]<<"\",\"Value\":"<<std::clamp(value,lo,hi)<<",\"Blend\":\"Overwrite\"}";
                }
                json<<"]}"; const auto text=json.str();
                expressions[std::string("app-")+tags[r]]=std::vector<csmByte>(text.begin(),text.end());
            }
            LOGI("model-specific face recipes registered: count=6 mouth_aperture=speech-owned model=ellen");
        }
        return continuousFaceReady;
    }
    void configureTouchRegions(bool whale) {
        touchDrawables.clear();
        const auto whaleHead={"Part99","Part2","Part4","Part6","Part7","Part15",
                              "Part39","Part154","Part151","Part11","Part12"};
        const auto ellenHead={"Part29","Part31","Part51","Part52","Part34","Part20",
                              "Part37","Part143","Part109","Part134"};
        const int bodyGroup=part(whale?"Part81":"Part60");
        // A model may put face art under several leaf parts instead of the named head folder.
        std::vector<int> headGroups;
        for(const char* id:whale?whaleHead:ellenHead) {
            const int index=part(id);
            if(index>=0) headGroups.push_back(index);
        }
        const auto* parents=_model->GetPartParentPartIndices();
        const int partCount=_model->GetPartCount();
        if(!parents||partCount<=0) return;
        std::array<int,2> counts{};
        for(int drawable=0;drawable<_model->GetDrawableCount();++drawable) {
            int parent=_model->GetDrawableParentPartIndex(drawable);
            int region=-1;
            for(int depth=0;depth<partCount&&parent>=0&&parent<partCount;++depth) {
                if(std::find(headGroups.begin(),headGroups.end(),parent)!=headGroups.end()) {
                    region=0;
                    break;
                }
                if(parent==bodyGroup) region=1;
                parent=parents[parent];
            }
            if(region>=0) {
                touchDrawables.emplace_back(drawable,region);
                ++counts[region];
            }
        }
        LOGI("touch regions configured: head_drawables=%d body_drawables=%d",counts[0],counts[1]);
    }
    std::array<float,8> touchBounds(int width,int height) const {
        // Same projection as the surface draw, including the currently eased framing.
        std::array<float,8> bounds{{1.f,1.f,0.f,0.f,1.f,1.f,0.f,0.f}};
        if(width<=0||height<=0||touchDrawables.empty()) return bounds;
        const float cw=_model->GetCanvasWidth(),ch=_model->GetCanvasHeight();
        if(!(cw>0&&ch>0)) return bounds;
        const float aspect=static_cast<float>(width)/height;
        const float sy=std::min(2.f/ch,2.f*aspect/cw)*.96f,sx=sy/aspect;
        const auto& framing=presentationCurrent;
        for(const auto& [drawable,region]:touchDrawables) {
            if(!_model->GetDrawableDynamicFlagIsVisible(drawable)||_model->GetDrawableOpacity(drawable)<.05f) continue;
            const auto* vertices=_model->GetDrawableVertexPositions(drawable);
            if(!vertices) continue;
            const int offset=region*4;
            for(int v=0;v<_model->GetDrawableVertexCount(drawable);++v) {
                const float x=.5f+vertices[v].X*sx*framing[0]*.5f+framing[1];
                const float y=.5f-vertices[v].Y*sy*framing[0]*.5f+framing[2];
                if(!std::isfinite(x)||!std::isfinite(y)) continue;
                bounds[offset]=std::min(bounds[offset],x);
                bounds[offset+1]=std::min(bounds[offset+1],y);
                bounds[offset+2]=std::max(bounds[offset+2],x);
                bounds[offset+3]=std::max(bounds[offset+3],y);
            }
        }
        return bounds;
    }
    bool configureJsPerformance(const std::vector<std::string>& ids) {
        jsChannels.clear();jsFrames=0;jsBlend=0;jsFrameAge=1000;
        if(ids.empty()||ids.size()>48) return false;
        std::set<int> allowed,body;
        for(int i:callInputIndices) if(i>=0) {allowed.insert(i);body.insert(i);}
        for(int i:faceIndices) if(i>=0) allowed.insert(i);
        for(int i:faceOverlayIndices) if(i>=0) allowed.insert(i);
        if(eyeSmileIndex>=0) allowed.insert(eyeSmileIndex);
        const int breath=parameter("ParamBreath");
        if(breath>=0) allowed.insert(breath);
        for(const char* id:{"ParamEyeBallX","ParamEyeBallY","ParamBrowLX","ParamBrowRX",
                "ParamBrowLAngle","ParamBrowRAngle","ParamBrowLForm","ParamBrowRForm",
                "ParamEyeLSmile","ParamEyeRSmile","ParamBrowRForm_Angry2","ParamBrowRForm_Angry"}) {
            const int i=parameter(id);if(i>=0) allowed.insert(i);
        }
        std::set<int> seen;
        for(const auto& id:ids) {
            const int i=parameter(id.c_str());
            if(i<0||i==mouthIndex||!allowed.count(i)||!seen.insert(i).second||
               (whaleInputGestures&&physicsOutputIds.count(_model->GetParameterId(i)))) {
                LOGE("JS parameter binding rejected: id=%s reason=absent-or-unowned",id.c_str());
                jsChannels.clear();return false;
            }
            const float lo=_model->GetParameterMinimumValue(i),center=_model->GetParameterDefaultValue(i),hi=_model->GetParameterMaximumValue(i);
            if(!std::isfinite(lo)||!std::isfinite(center)||!std::isfinite(hi)||lo>center||center>hi||lo>=hi) {
                jsChannels.clear();return false;
            }
            const bool after=!whaleInputGestures&&physicsOutputIds.count(_model->GetParameterId(i));
            jsChannels.push_back({id,i,after,!body.count(i)&&i!=breath,center,center});
        }
        LOGI("JS parameter binding ready: channels=%zu mouth_aperture=tts arm=author-fsm physics-order=model-specific",jsChannels.size());
        return true;
    }
    bool setJsParameters(const std::vector<float>& values) {
        if(jsChannels.empty()||values.size()!=jsChannels.size()) return false;
        for(float v:values) if(!std::isfinite(v)) return false;
        for(size_t n=0;n<values.size();++n) {
            auto& channel=jsChannels[n];
            // Increase torso and facial-shape contrast around the rig's neutral value.
            // Blink, gaze, author arm transitions and TTS mouth aperture keep their existing owners.
            const int i=channel.index;
            const auto owns=[i](auto begin,auto end){return std::find(begin,end,i)!=end;};
            const bool head=owns(callInputIndices.begin(),callInputIndices.begin()+3);
            const bool torso=owns(callInputIndices.begin()+3,callInputIndices.end());
            const bool faceShape=owns(faceIndices.begin(),faceIndices.begin()+3);
            const float gain=torso?(whaleInputGestures?2.10f:1.85f):
                head?(whaleInputGestures?1.55f:1.45f):
                faceShape?(whaleInputGestures?1.25f:1.38f):1.f;
            const float center=_model->GetParameterDefaultValue(i);
            const float lo=_model->GetParameterMinimumValue(i),hi=_model->GetParameterMaximumValue(i);
            const float safeSpan=(head||torso)?.70f:1.f;
            channel.target=std::clamp(center+(values[n]-center)*gain,
                center-(center-lo)*safeSpan,center+(hi-center)*safeSpan);
        }
        jsFrameAge=0;++jsFrames;return true;
    }
    void applyJsPerformance(float dt,bool afterPhysics) {
        if(!afterPhysics) {
            jsFrameAge+=std::max(0.f,dt);
            const float target=jsFrames>0&&jsFrameAge<.8f?1.f:0.f;
            jsBlend+=(target-jsBlend)*(1.f-std::exp(-std::max(0.f,dt)/.16f));
            const float alpha=1.f-std::exp(-std::max(0.f,dt)/.10f);
            for(auto& channel:jsChannels) channel.current+=(channel.target-channel.current)*alpha;
        }
        if(jsBlend<.001f) return;
        for(const auto& channel:jsChannels) {
            if(channel.afterPhysics!=afterPhysics||(channel.face&&manualExpressionActive)||
               (!channel.face&&channel.id!="ParamBreath"&&!activeCallGesture.empty())) continue;
            const float baseline=_model->GetParameterValue(channel.index);
            _model->SetParameterValue(channel.index,std::clamp(baseline+(channel.current-baseline)*jsBlend,
                _model->GetParameterMinimumValue(channel.index),_model->GetParameterMaximumValue(channel.index)));
        }
        if(afterPhysics) {
            jsLogElapsed+=dt;
            if(jsLogElapsed>=5.f) {
                LOGI("JS performance applied: frames=%llu age_ms=%.1f blend=%.3f channels=%zu legacy_body=%s manual_face=%d",
                    jsFrames,jsFrameAge*1000,jsBlend,jsChannels.size(),jsFrameAge<.8f?"off":"fallback",manualExpressionActive);
                jsLogElapsed=0;
            }
        }
    }
    std::string jsDiagnostics() const {
        std::ostringstream out;out.precision(8);
        out<<"{\"jsFrames\":"<<jsFrames<<",\"frameAgeMs\":"<<jsFrameAge*1000
           <<",\"blend\":"<<jsBlend<<",\"active\":"<<(jsFrames>0&&jsFrameAge<.8f?"true":"false")<<",\"parameters\":{";
        for(size_t n=0;n<jsChannels.size();++n) {
            if(n)out<<",";
            out<<"\""<<jsChannels[n].id<<"\":"<<_model->GetParameterValue(jsChannels[n].index);
        }
        out<<"},\"crossEyeStyleActive\":"<<(crossEyeStyleActive?"true":"false")<<",\"normalEyePartOpacities\":[";
        for(size_t n=0;n<normalEyePartIndices.size();++n) {if(n)out<<",";out<<_model->GetPartOpacity(normalEyePartIndices[n]);}
        out<<"]}";return out.str();
    }
    void updatePerformance(float dt,bool afterPhysics=false) {
        // Continuous, phase-stable motion: no idle timer, random pose jumps, inference or per-frame allocation.
        if(afterPhysics&&whaleInputGestures) return;
        if(!afterPhysics) {
            performanceTime+=std::max(0.f,dt);
            performanceSpeech+=(mouth-performanceSpeech)*(1.f-std::exp(-std::max(0.f,dt)/.18f));
        }
        const float happy=emotionCurrent[0],sad=emotionCurrent[1],angry=emotionCurrent[2],surprise=emotionCurrent[3],shy=emotionCurrent[4];
        const float energy=std::clamp(.40f*happy+.25f*sad+.70f*angry+.80f*surprise+.35f*shy+performanceSpeech,0.f,1.f);
        const float gain=(whaleInputGestures?.55f:1.35f)*(activeCallGesture.empty()?1.f:.25f)*(1.f-jsBlend);
        const float t=performanceTime;
        const std::array<float,6> waves={
            .70f*std::sin(t*.53f)+.30f*std::sin(t*1.17f),
            .62f*std::sin(t*.79f+.7f)+.38f*std::sin(t*1.61f),
            .78f*std::sin(t*.61f+.3f)+.22f*std::sin(t*1.31f),
            std::sin(t*.48f),std::sin(t*.87f+.5f),std::sin(t*.56f+.2f)};
        const std::array<float,6> base={.13f,.10f,.11f,.18f,.12f,.16f};
        for(size_t n=0;n<callInputIndices.size();++n) {
            // Ellen body angles are authored physics outputs; apply only their bounded residual afterwards.
            if(!whaleInputGestures&&((n>=3)!=afterPhysics)) continue;
            const int i=callInputIndices[n]; if(i<0) continue;
            const float lo=_model->GetParameterMinimumValue(i),center=_model->GetParameterDefaultValue(i),hi=_model->GetParameterMaximumValue(i);
            if(!std::isfinite(lo)||!std::isfinite(center)||!std::isfinite(hi)||lo>=hi) continue;
            const float unit=std::clamp(waves[n]*base[n]*(1.f+energy*.85f)*gain,-.45f,.45f);
            const float span=unit>=0?hi-center:center-lo;
            _model->SetParameterValue(i,std::clamp(_model->GetParameterValue(i)+unit*span,lo,hi));
        }
        if(!afterPhysics) performanceLogElapsed+=dt;
        if(performanceLogElapsed>=5.f&&(whaleInputGestures||afterPhysics)) {
            LOGI("continuous performance: model=%s speech=%.3f mood_energy=%.3f gain=%.3f mode=continuous-no-idle-timer",
                whaleInputGestures?"whale":"ellen",performanceSpeech,energy,gain);
            performanceLogElapsed=0;
        }
    }
    void setPresentationTransform(float scale,float x,float y) {
        presentationTarget={std::isfinite(scale)?std::clamp(scale,.65f,2.25f):1.f,
            std::isfinite(x)?std::clamp(x,-.5f,.5f):0.f,std::isfinite(y)?std::clamp(y,-.5f,.5f):0.f};
    }
    void setEmotionWeights(const std::array<float,5>& values) {
        float sum=0;
        for(size_t n=0;n<values.size();++n) {
            emotionTarget[n]=std::isfinite(values[n])?std::clamp(values[n],0.f,1.f):0.f;
            sum+=emotionTarget[n];
        }
        if(sum>1.f) for(float& value:emotionTarget) value/=sum;
    }
    void updateContinuousFace(float dt) {
        if(!continuousFaceReady) return;
        const float alpha=1.f-std::exp(-std::max(0.f,dt)/.22f);
        for(size_t n=0;n<emotionCurrent.size();++n) emotionCurrent[n]+=(emotionTarget[n]-emotionCurrent[n])*alpha;
        manualFaceBlend+=((manualExpressionActive?1.f:0.f)-manualFaceBlend)*(1.f-std::exp(-std::max(0.f,dt)/.25f));
        const float gain=(1.f-manualFaceBlend)*(1.f-jsBlend);
        // Rendering contrast is independent of the slow, cumulative mood state.
        const auto contrast=[](float value){return 1.f-std::exp(-2.4f*value);};
        const float happy=contrast(emotionCurrent[0]),sad=contrast(emotionCurrent[1]),angry=contrast(emotionCurrent[2]),
            surprised=contrast(emotionCurrent[3]),shy=contrast(emotionCurrent[4]);
        const float mouthShape=gain*std::clamp(.64f*happy-.50f*sad-.42f*angry+.12f*surprised+.45f*shy,-.75f,.75f);
        const float browShape=gain*std::clamp(.24f*happy+.28f*sad-.70f*angry+.70f*surprised+.14f*shy,-.75f,.75f);
        for(size_t n=0;n<3;++n) {
            const int i=faceIndices[n]; if(i<0) continue;
            const float shape=n==0?mouthShape:browShape;
            const float lo=_model->GetParameterMinimumValue(i),center=_model->GetParameterDefaultValue(i),hi=_model->GetParameterMaximumValue(i);
            const float span=shape>=0?hi-center:center-lo;
            _model->SetParameterValue(i,std::clamp(_model->GetParameterValue(i)+shape*span,lo,hi));
        }
        const float aperture=1.f+gain*(-.22f*happy-.16f*sad-.24f*angry+.35f*surprised-.20f*shy);
        for(size_t n=3;n<faceIndices.size();++n) {
            const int i=faceIndices[n]; if(i<0) continue;
            _model->SetParameterValue(i,std::clamp(_model->GetParameterValue(i)*aperture,
                _model->GetParameterMinimumValue(i),_model->GetParameterMaximumValue(i)));
        }
        if(eyeSmileIndex>=0) {
            const float center=_model->GetParameterDefaultValue(eyeSmileIndex),hi=_model->GetParameterMaximumValue(eyeSmileIndex);
            const float smile=gain*std::clamp(.80f*happy+.55f*shy-.25f*angry,0.f,1.f);
            _model->SetParameterValue(eyeSmileIndex,std::clamp(_model->GetParameterValue(eyeSmileIndex)+smile*(hi-center),
                _model->GetParameterMinimumValue(eyeSmileIndex),hi));
        }
        const auto ease=[](float value,float from,float to){const float u=std::clamp((value-from)/(to-from),0.f,1.f);return u*u*(3.f-2.f*u);};
        const std::array<float,4> effects={ease(happy,.32f,.82f),ease(sad,.24f,.82f),ease(angry,.24f,.82f),
            std::clamp(shy*.95f+happy*.14f,0.f,1.f)};
        for(size_t n=0;n<faceOverlayIndices.size();++n) {
            const int i=faceOverlayIndices[n]; if(i<0) continue;
            _model->SetParameterValue(i,std::clamp(_model->GetParameterValue(i)+gain*effects[n],
                _model->GetParameterMinimumValue(i),_model->GetParameterMaximumValue(i)));
        }
        faceLogElapsed+=dt;
        bool changed=false;
        for(size_t n=0;n<emotionCurrent.size();++n) changed=changed||std::abs(emotionCurrent[n]-emotionLastLog[n])>.005f;
        if(faceLogElapsed>=5.f&&changed) {
            LOGI("continuous face: weights=%.3f,%.3f,%.3f,%.3f,%.3f manual=%.3f mouth_shape=%.3f brow_shape=%.3f eye_scale=%.3f",
                emotionCurrent[0],emotionCurrent[1],emotionCurrent[2],emotionCurrent[3],emotionCurrent[4],manualFaceBlend,mouthShape,browShape,aperture);
            emotionLastLog=emotionCurrent; faceLogElapsed=0; faceObservationDue=true;
        }
    }
    void observeContinuousFace() {
        if(!faceObservationDue) return;
        std::array<float,5> actual{}; unsigned support=0;
        for(size_t n=0;n<faceIndices.size();++n) if(faceIndices[n]>=0) {
            actual[n]=_model->GetParameterValue(faceIndices[n]); support|=1u<<n;
        }
        LOGI("continuous face applied: support_mask=%u mouth=%.4f brow_l=%.4f brow_r=%.4f eye_l=%.4f eye_r=%.4f",
            support,actual[0],actual[1],actual[2],actual[3],actual[4]);
        std::array<float,4> effects{};
        for(size_t n=0;n<effects.size();++n) if(faceOverlayIndices[n]>=0) effects[n]=_model->GetParameterValue(faceOverlayIndices[n]);
        LOGI("automatic face effects applied: happy_eyes=%.4f tears=%.4f dark_face=%.4f blush=%.4f eye_smile=%.4f manual=%.3f",
            effects[0],effects[1],effects[2],effects[3],eyeSmileIndex>=0?_model->GetParameterValue(eyeSmileIndex):0.f,manualFaceBlend);
        faceObservationDue=false;
    }
    bool configureArmPoses(const std::vector<csmByte>& up,const std::vector<csmByte>& down) {
        if(!whaleInputGestures) return false;
        resetArmPose("configure"); armPairReady=false;
        std::array<float,5> downEnd{},downStart{},upStart{};
        const std::vector<csmByte>* files[2]={&up,&down};
        for(int side=0;side<2;++side) {
            const auto& content=*files[side];
            CubismMotionJson json(content.data(),content.size());
            if(!json.IsValid()||json.IsMotionLoop()||json.GetMotionCurveCount()!=5||
               !(json.GetMotionDuration()>0&&json.GetMotionDuration()<=3)) {
                LOGE("arm pair rejected: side=%d phase=json-or-shape",side); return false;
            }
            auto* checked=CubismMotion::Create(content.data(),content.size(),nullptr,nullptr,true);
            if(!checked) { LOGE("arm pair rejected: side=%d phase=sdk-consistency",side); return false; }
            ACubismMotion::Delete(checked);
            std::set<int> seen;
            for(int curve=0;curve<5;++curve) {
                if(std::strcmp(json.GetMotionCurveTarget(curve),"Parameter")!=0) return false;
                int axis=-1;
                for(size_t n=0;n<armPoseParameters.size();++n)
                    if(json.GetMotionCurveId(curve)==CubismFramework::GetIdManager()->GetId(armPoseParameters[n])) axis=n;
                if(axis<0||!seen.insert(axis).second) return false;
                const int index=armIndices[axis]=parameter(armPoseParameters[axis]); if(index<0) return false;
                const int count=json.GetMotionCurveSegmentCount(curve); if(count<2) return false;
                const float first=json.GetMotionCurveSegment(curve,1),last=json.GetMotionCurveSegment(curve,count-1);
                const float lo=_model->GetParameterMinimumValue(index),hi=_model->GetParameterMaximumValue(index);
                if(!std::isfinite(first)||!std::isfinite(last)||first<lo-.0001f||first>hi+.0001f||last<lo-.0001f||last>hi+.0001f) {
                    LOGE("arm pair rejected: side=%d id=%s first=%.5f last=%.5f min=%.5f max=%.5f",side,armPoseParameters[axis],first,last,lo,hi);
                    return false;
                }
                if(side==0) { upStart[axis]=first; armUpEnd[axis]=last; }
                else { downStart[axis]=first; downEnd[axis]=last; }
            }
        }
        for(size_t n=0;n<armIndices.size();++n) {
            const int i=armIndices[n];
            if(std::abs(downEnd[n]-upStart[n])>.01f) { LOGE("arm pair rejected: rest endpoints do not match id=%s",armPoseParameters[n]); return false; }
            if(std::abs(armUpEnd[n]-downStart[n])>.01f) { LOGE("arm pair rejected: raised endpoints do not match id=%s",armPoseParameters[n]); return false; }
            LOGI("arm parameter range: id=%s min=%.4f default=%.4f max=%.4f up_end=%.4f down_end=%.4f",
                armPoseParameters[n],_model->GetParameterMinimumValue(i),_model->GetParameterDefaultValue(i),
                _model->GetParameterMaximumValue(i),armUpEnd[n],downEnd[n]);
        }
        armUpMotion=up; armDownMotion=down; armPairReady=true;
        LOGI("arm pair registered: channels=5 sdk-consistency=passed loop=false excluded=Param56,Param57"); return true;
    }
    bool startArmMotion(bool raised,const char* reason) {
        const auto& content=raised?armUpMotion:armDownMotion;
        auto* motion=CubismMotion::Create(content.data(),content.size());
        if(!motion) { LOGE("arm motion failed: raised=%d phase=sdk-json",raised); resetArmPose("parse-failed"); return false; }
        motion->SetLoop(false); armMotionManager.StopAllMotions();
        armMotionManager.StartMotionPriority(motion,true,3);
        armState=raised?1:3; armHoldElapsed=0; lowerAfterRaise=false;
        LOGI("arm motion start: state=%s reason=%s duration=%.3f independent=true",raised?"raising":"lowering",reason,motion->GetDuration());
        return true;
    }
    bool requestArmPose(bool raised) {
        if(!armPairReady) { LOGE("arm pose unavailable: pair-not-ready"); return false; }
        if(raised) {
            if(armState!=0) { LOGI("arm raise ignored: busy state=%d",armState); return false; }
            for(size_t n=0;n<armIndices.size();++n) armCurrent[n]=_model->GetParameterValue(armIndices[n]);
            armPeak.fill(0);
            return startArmMotion(true,"manual-or-chat");
        }
        if(armState==0||armState==3) return true;
        if(armState==1) { lowerAfterRaise=true; LOGI("arm lower scheduled: finish-raising-first"); return true; }
        return startArmMotion(false,"requested");
    }
    void resetArmPose(const char* reason) {
        armMotionManager.StopAllMotions();
        if(armState!=0) LOGI("arm pose reset: reason=%s previous=%d",reason,armState);
        armState=0; armHoldElapsed=0; lowerAfterRaise=false;
        // Restore only owned arm channels, never the author's light / costume parameters.
        if(_model) for(int i:armIndices) if(i>=0) _model->SetParameterValue(i,_model->GetParameterDefaultValue(i));
    }
    void updateArmPose(float dt) {
        if(armState==0) return;
        for(size_t n=0;n<armIndices.size();++n) _model->SetParameterValue(armIndices[n],armCurrent[n]);
        if(armState==2) {
            armHoldElapsed+=dt;
            if(armHoldElapsed>=1.4f) startArmMotion(false,"auto-after-1.4s");
        } else {
            armMotionManager.UpdateMotion(_model,dt);
            for(size_t n=0;n<armIndices.size();++n) {
                armCurrent[n]=_model->GetParameterValue(armIndices[n]);
                armPeak[n]=std::max(armPeak[n],std::abs(armCurrent[n]));
            }
            if(armMotionManager.IsFinished()) {
                if(armState==1) {
                    armCurrent=armUpEnd;
                    for(size_t n=0;n<armIndices.size();++n) _model->SetParameterValue(armIndices[n],armCurrent[n]);
                    armState=2; armHoldElapsed=0;
                    LOGI("arm pose holding: automatic-lower-after=1.400");
                    if(lowerAfterRaise) startArmMotion(false,"requested-during-raise");
                } else {
                    armState=0; lowerAfterRaise=false;
                    LOGI("arm pose down: overlay=released peak_abs=%.4f,%.4f,%.4f,%.4f,%.4f",
                        armPeak[0],armPeak[1],armPeak[2],armPeak[3],armPeak[4]);
                }
            }
        }
    }
    void resetMotionState() {
        if(!_model) return;
        _model->LoadParameters(); _motionManager->StopAllMotions();
        finishCallGesture("reset",true);
        for(int i:callInputIndices) if(i>=0) _model->SetParameterValue(i,_model->GetParameterDefaultValue(i));
        resetArmPose("reset"); _model->SaveParameters();
        dragX=dragY=0;
        LOGI("application motion reset: head-inputs=default arm=down expression=preserved");
    }
    void add(const char* name,float value) { int i=parameter(name); if(i>=0) _model->AddParameterValue(i,value); }
    void update(float dt) {
        if(!_model) return;
        // Part opacity is not a saved parameter: restore before this frame's motion / expression layers.
        restoreCrossEyeParts();
        const float framingAlpha=1.f-std::exp(-std::max(0.f,dt)/.25f);
        for(size_t n=0;n<presentationCurrent.size();++n) presentationCurrent[n]+=(presentationTarget[n]-presentationCurrent[n])*framingAlpha;
        _model->LoadParameters();
        if(_motionManager->IsFinished()) {
            finishCallGesture("finished",true);
            if(!idleFiles.empty()) play(idleFiles[idleIndex++%idleFiles.size()],1);
        }
        if(!activeCallGesture.empty()) callElapsed+=dt;
        const bool animated=_motionManager->UpdateMotion(_model,dt); _model->SaveParameters();
        updateArmPose(dt); // Separate cache: idle / head motions never overwrite a held arm pose.
        // This Whale author's idle has no eye-open curves; an always-playing idle must not suppress blinking.
        if(_eyeBlink&&jsBlend<.05f&&(!animated||continuousFaceReady)) _eyeBlink->UpdateParameters(_model,dt);
        if(!appFaceRecipes) updateContinuousFace(dt);
        if(!appFaceRecipes) _expressionManager->UpdateMotion(_model,dt);
        _breath->UpdateParameters(_model,dt);
        updatePerformance(dt); // Inputs before physics; never write the author's simulated outputs.
        applyJsPerformance(dt,false);
        add("ParamAngleX",dragX*20.f); add("ParamAngleY",dragY*15.f);
        if(whaleInputGestures) clampCallInputs();
        float mouthTarget=0;
        if(mouthIndex>=0) {
            const float lo=_model->GetParameterMinimumValue(mouthIndex),hi=_model->GetParameterMaximumValue(mouthIndex);
            const float closed=_model->GetParameterDefaultValue(mouthIndex);
            mouthTarget=std::clamp(closed+mouth*(hi-closed),lo,hi);
            // The author's lip, cheek and tongue physics must see this frame's speech input.
            // LoadParameters above restores the motion baseline on every frame.
            _model->SetParameterValue(mouthIndex,mouthTarget);
        }
        if(_physics) _physics->Evaluate(_model,dt);
        updatePerformance(dt,true);
        applyJsPerformance(dt,true);
        if(appFaceRecipes) {
            updateContinuousFace(dt);
            _expressionManager->UpdateMotion(_model,dt);
        }
        if(mouthIndex>=0) {
            const float physicsValue=_model->GetParameterValue(mouthIndex);
            // Speech owns the aperture; retain the final value after any physics outputs.
            _model->SetParameterValue(mouthIndex,mouthTarget);
            const float actual=_model->GetParameterValue(mouthIndex);
            const bool active=mouth>.001f;
            mouthLogElapsed+=std::max(0.f,dt);
            mouthInputPeak=std::max(mouthInputPeak,mouth);
            mouthAppliedPeak=std::max(mouthAppliedPeak,actual);
            if((active&&!mouthWasActive)||(active&&mouthLogElapsed>=1.f)||(!active&&mouthWasActive)) {
                LOGI("speech mouth applied: driver=ParamMouthOpenY input=%.4f target=%.4f after_physics=%.4f actual=%.4f input_peak=%.4f actual_peak=%.4f physics=%s order=before-and-after",
                    mouth,mouthTarget,physicsValue,actual,mouthInputPeak,mouthAppliedPeak,_physics?"enabled":"absent");
                mouthLogElapsed=0; mouthInputPeak=mouthAppliedPeak=0;
            }
            mouthWasActive=active;
        }
        if(!whaleInputGestures) clampCallInputs();
        observeCallPose();
        observeContinuousFace();
        applyCrossEyeParts(); // Read final key5 after automatic, manual expression and physics updates.
        if(!hiddenPart.empty()) _model->SetPartOpacity(CubismFramework::GetIdManager()->GetId(hiddenPart.c_str()),0.f);
        _model->Update();
    }
    void clearExpression() {
        if(!manualExpressionActive) return;
        static const char neutral[]="{\n\"Type\": \"Live2D Expression\",\n\"Parameters\": []\n}\n";
        auto* motion=CubismExpressionMotion::Create(reinterpret_cast<const csmByte*>(neutral),sizeof(neutral)-1);
        if(!motion) { LOGE("expression neutral fade rejected by SDK"); return; }
        motion->SetFadeInTime(.6f); motion->SetFadeOutTime(.6f);
        _expressionManager->StartMotionPriority(motion,true,10); manualExpressionActive=false;
        LOGI("expression released: fade=0.600 automatic-face=restored");
    }
};
// Owned by the single serialized GL thread; never carry GL names into another context.
static AppModel* g_model=nullptr;
static GLuint g_fbo=0,g_color=0;
static int g_width=0,g_height=0;
static bool g_drawStatsLogged=false;
static std::vector<csmByte> g_row;
static void dispose() {
    delete g_model; g_model=nullptr;
    if(g_fbo) glDeleteFramebuffers(1,&g_fbo); if(g_color) glDeleteTextures(1,&g_color);
    g_fbo=g_color=0; g_width=g_height=0; g_row.clear(); g_drawStatsLogged=false;
    if(g_started) Rendering::CubismRenderer::StaticRelease();
}
static bool target(int width,int height) {
    if(g_fbo&&width==g_width&&height==g_height) return true;
    if(g_fbo) glDeleteFramebuffers(1,&g_fbo); if(g_color) glDeleteTextures(1,&g_color);
    glGenTextures(1,&g_color); glBindTexture(GL_TEXTURE_2D,g_color);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
    glGenFramebuffers(1,&g_fbo); glBindFramebuffer(GL_FRAMEBUFFER,g_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,g_color,0);
    if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) return false;
    g_width=width; g_height=height; g_row.resize(width*4); return true;
}
extern "C" {
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeInit(JNIEnv* e,jobject,jstring dir) {
    g_shaderDir=utf8(e,dir);
    if(!g_started) {
        static CubismFramework::Option options;
        options.LoadFileFunction=loadShader; options.ReleaseBytesFunction=releaseShader;
        options.LoggingLevel=CubismFramework::Option::LogLevel_Warning;
        CubismFramework::StartUp(&g_allocator,&options); CubismFramework::Initialize(); g_started=true;
    }
}
JNIEXPORT jboolean JNICALL Java_com_prts_client_live2d_Live2DView_nativeSetup(JNIEnv* e,jobject,jbyteArray moc,jbyteArray json,jbyteArray physics,jstring hidden) {
    dispose(); g_model=new AppModel();
    if(!g_model->setup(bytes(e,moc),bytes(e,json),bytes(e,physics),utf8(e,hidden))) { LOGE("model setup failed"); dispose(); return JNI_FALSE; }
    return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL Java_com_prts_client_live2d_Live2DView_nativeBindTexture(JNIEnv* e,jobject,jint i,jobject buffer,jint w,jint h) {
    auto* p=static_cast<csmByte*>(e->GetDirectBufferAddress(buffer));
    if(!g_model||!p||e->GetDirectBufferCapacity(buffer)<static_cast<jlong>(w)*h*4) return JNI_FALSE;
    return g_model->texture(i,p,w,h)?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeAddExpressionFile(JNIEnv* e,jobject,jstring name,jbyteArray data) {
    if(g_model) g_model->expressions[utf8(e,name)]=bytes(e,data);
}
JNIEXPORT jboolean JNICALL Java_com_prts_client_live2d_Live2DView_nativeAddMotionFile(JNIEnv* e,jobject,jstring name,jbyteArray data,jboolean idle) {
    const auto key=utf8(e,name); auto content=bytes(e,data);
    if(!g_model) { LOGE("motion registration failed: name=%s phase=model-not-ready",key.c_str()); return JNI_FALSE; }
    if(isCallGesture(key)) {
        auto* checked=CubismMotion::Create(content.data(),content.size(),nullptr,nullptr,true);
        if(!checked) {
            LOGE("motion registration failed: name=%s bytes=%zu phase=sdk-json-or-consistency",key.c_str(),content.size());
            return JNI_FALSE;
        }
        LOGI("motion registered: name=%s bytes=%zu duration=%.3f sdk-consistency=passed",key.c_str(),content.size(),checked->GetDuration());
        ACubismMotion::Delete(checked);
    }
    g_model->motions[key]=std::move(content); if(idle) g_model->idleFiles.push_back(key);
    return JNI_TRUE;
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeSetExpression(JNIEnv* e,jobject,jstring name) { if(g_model) g_model->expression(utf8(e,name)); }
JNIEXPORT jboolean JNICALL Java_com_prts_client_live2d_Live2DView_nativeConfigureJsPerformance(JNIEnv* e,jobject,jobjectArray names,jfloatArray bounds) {
    if(!g_model||!names||!bounds) return JNI_FALSE;
    const jsize count=e->GetArrayLength(names);if(count<1||count>48)return JNI_FALSE;
    if(e->GetArrayLength(bounds)!=count*3)return JNI_FALSE;
    std::vector<float> expected(count*3);e->GetFloatArrayRegion(bounds,0,count*3,expected.data());
    std::vector<std::string> ids;ids.reserve(count);
    for(jsize n=0;n<count;++n) {
        auto value=static_cast<jstring>(e->GetObjectArrayElement(names,n));
        if(!value)return JNI_FALSE;
        ids.push_back(utf8(e,value));e->DeleteLocalRef(value);
        const int i=g_model->parameter(ids.back().c_str());if(i<0)return JNI_FALSE;
        const std::array<float,3> actual={g_model->GetModel()->GetParameterMinimumValue(i),g_model->GetModel()->GetParameterDefaultValue(i),g_model->GetModel()->GetParameterMaximumValue(i)};
        for(size_t k=0;k<actual.size();++k) if(!std::isfinite(expected[n*3+k])||std::abs(expected[n*3+k]-actual[k])>.0001f) {
            LOGE("JS profile range mismatch: id=%s",ids.back().c_str());return JNI_FALSE;
        }
    }
    return g_model->configureJsPerformance(ids)?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_com_prts_client_live2d_Live2DView_nativeSetJsParameters(JNIEnv* e,jobject,jfloatArray values) {
    if(!g_model||!values)return JNI_FALSE;
    const jsize count=e->GetArrayLength(values);if(count<1||count>48)return JNI_FALSE;
    std::vector<float> target(count);e->GetFloatArrayRegion(values,0,count,target.data());
    return g_model->setJsParameters(target)?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jstring JNICALL Java_com_prts_client_live2d_Live2DView_nativeJsDiagnostics(JNIEnv* e,jobject) {
    const std::string value=g_model?g_model->jsDiagnostics():"{\"active\":false,\"reason\":\"model-not-loaded\"}";
    return e->NewStringUTF(value.c_str());
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeClearExpression(JNIEnv*,jobject) { if(g_model) g_model->clearExpression(); }
JNIEXPORT jboolean JNICALL Java_com_prts_client_live2d_Live2DView_nativeConfigureContinuousFace(JNIEnv*,jobject,jboolean whale,jint overlayMask) {
    return g_model&&g_model->configureContinuousFace(whale==JNI_TRUE,overlayMask)?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeSetPresentationTransform(JNIEnv*,jobject,jfloat scale,jfloat x,jfloat y) {
    if(g_model) g_model->setPresentationTransform(scale,x,y);
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeSetEmotionWeights(JNIEnv*,jobject,jfloat happy,jfloat sad,jfloat angry,jfloat surprised,jfloat shy) {
    if(g_model) g_model->setEmotionWeights({happy,sad,angry,surprised,shy});
}
JNIEXPORT jstring JNICALL Java_com_prts_client_live2d_Live2DView_nativePrepareCallGestureRig(JNIEnv* e,jobject,jboolean useWhale) {
    const auto profile=g_model?g_model->prepareCallGestureRig(useWhale==JNI_TRUE):std::string();
    return e->NewStringUTF(profile.c_str());
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativePlayMotion(JNIEnv* e,jobject,jstring name) { if(g_model) g_model->play(utf8(e,name),3); }
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeSetDrag(JNIEnv*,jobject,jfloat x,jfloat y) {
    if(g_model) { g_model->dragX=std::clamp(x,-1.f,1.f); g_model->dragY=std::clamp(y,-1.f,1.f); }
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeSetMouth(JNIEnv*,jobject,jfloat value) {
    if(g_model) g_model->mouth=std::isfinite(value)?std::clamp(value,0.f,1.f):0.f;
}
JNIEXPORT jint JNICALL Java_com_prts_client_live2d_Live2DView_nativeUpdate(JNIEnv*,jobject,jfloat dt) {
    if(g_model) { g_model->update(std::clamp(dt,0.f,.1f)); return g_model->armState; } return 0;
}
JNIEXPORT jboolean JNICALL Java_com_prts_client_live2d_Live2DView_nativeConfigureArmPoses(JNIEnv* e,jobject,jbyteArray up,jbyteArray down) {
    return g_model&&g_model->configureArmPoses(bytes(e,up),bytes(e,down))?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeRequestArmPose(JNIEnv*,jobject,jboolean raised) {
    if(g_model) g_model->requestArmPose(raised==JNI_TRUE);
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeResetMotionState(JNIEnv*,jobject) { if(g_model) g_model->resetMotionState(); }
JNIEXPORT jboolean JNICALL Java_com_prts_client_live2d_Live2DView_nativeRenderFrame(JNIEnv* e,jobject,jobject buffer,jint w,jint h) {
    auto* p=static_cast<csmByte*>(e->GetDirectBufferAddress(buffer));
    if(!g_model||!p||w<=0||h<=0||e->GetDirectBufferCapacity(buffer)<static_cast<jlong>(w)*h*4||!target(w,h)) return JNI_FALSE;
    auto* r=g_model->GetRenderer<Rendering::CubismRenderer_OpenGLES2>();
    const float cw=g_model->GetModel()->GetCanvasWidth(),ch=g_model->GetModel()->GetCanvasHeight();
    if(!(cw>0&&ch>0)) return JNI_FALSE;
    const float aspect=static_cast<float>(w)/h,sy=std::min(2.f/ch,2.f*aspect/cw)*.96f,sx=sy/aspect;
    const auto& framing=g_model->presentationCurrent;
    CubismMatrix44 matrix; csmFloat32 m[16]={sx*framing[0],0,0,0,0,sy*framing[0],0,0,0,0,1,0,framing[1]*2.f,-framing[2]*2.f,0,1};
    matrix.SetMatrix(m); r->SetMvpMatrix(&matrix);
    glBindFramebuffer(GL_FRAMEBUFFER,g_fbo); glViewport(0,0,w,h);
    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE); glDisable(GL_SCISSOR_TEST); glClearColor(0,0,0,0); glClear(GL_COLOR_BUFFER_BIT);
    if(!g_drawStatsLogged) {
        auto* model=g_model->GetModel();int visible=0,changed=0,masked=0;
        const auto* counts=model->GetDrawableMaskCounts();const auto* masks=model->GetDrawableMasks();
        std::set<std::vector<csmInt32>> clipSets;
        for(int i=0;i<model->GetDrawableCount();++i) {
            visible+=model->GetDrawableDynamicFlagIsVisible(i)?1:0;
            changed+=model->GetDrawableDynamicFlagVertexPositionsDidChange(i)?1:0;
            if(counts&&counts[i]>0) {++masked;clipSets.insert(std::vector<csmInt32>(masks[i],masks[i]+counts[i]));}
        }
        LOGI("draw stats total=%d visible=%d verticesChanged=%d masked=%d uniqueClips=%zu",model->GetDrawableCount(),visible,changed,masked,clipSets.size());
        g_drawStatsLogged=true;
    }
    r->DrawModel(); // Official save / mask / sort / draw / restore, no SDK-internal hooks.
    glBindFramebuffer(GL_FRAMEBUFFER,g_fbo); glPixelStorei(GL_PACK_ALIGNMENT,1);
    glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,p); const GLenum error=glGetError(); glBindFramebuffer(GL_FRAMEBUFFER,0);
    if(error!=GL_NO_ERROR) { LOGE("render/readback GL error=0x%x",error); return JNI_FALSE; }
    const size_t stride=w*4;
    for(int y=0;y<h/2;++y) { auto* a=p+y*stride; auto* b=p+(h-1-y)*stride;
        std::memcpy(g_row.data(),a,stride); std::memcpy(a,b,stride); std::memcpy(b,g_row.data(),stride); }
    return JNI_TRUE;
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeConfigureTouchRegions(JNIEnv*,jobject,jboolean whale) {
    if(g_model) g_model->configureTouchRegions(whale==JNI_TRUE);
}
JNIEXPORT jfloatArray JNICALL Java_com_prts_client_live2d_Live2DView_nativeTouchBounds(JNIEnv* env,jobject,jint width,jint height) {
    if(!g_model) return nullptr;
    const auto bounds=g_model->touchBounds(width,height);
    auto result=env->NewFloatArray(bounds.size());
    if(result) env->SetFloatArrayRegion(result,0,bounds.size(),bounds.data());
    return result;
}
JNIEXPORT jboolean JNICALL Java_com_prts_client_live2d_Live2DView_nativeDrawSurface(JNIEnv*,jobject,jint w,jint h) {
    if(!g_model||w<=0||h<=0) return JNI_FALSE;
    auto* r=g_model->GetRenderer<Rendering::CubismRenderer_OpenGLES2>();
    const float cw=g_model->GetModel()->GetCanvasWidth(),ch=g_model->GetModel()->GetCanvasHeight();
    if(!(cw>0&&ch>0)) return JNI_FALSE;
    const float aspect=static_cast<float>(w)/h,sy=std::min(2.f/ch,2.f*aspect/cw)*.96f,sx=sy/aspect;
    const auto& framing=g_model->presentationCurrent;
    CubismMatrix44 matrix; csmFloat32 m[16]={sx*framing[0],0,0,0,0,sy*framing[0],0,0,0,0,1,0,framing[1]*2.f,-framing[2]*2.f,0,1};
    matrix.SetMatrix(m); r->SetMvpMatrix(&matrix);
    // The EGL window owns this framebuffer. Cubism saves/restores it around its mask passes.
    glBindFramebuffer(GL_FRAMEBUFFER,0); glViewport(0,0,w,h);
    glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE); glDisable(GL_SCISSOR_TEST);
    glClearColor(0,0,0,0); glClear(GL_COLOR_BUFFER_BIT);
    r->DrawModel();
    const GLenum error=glGetError();
    if(error!=GL_NO_ERROR) { LOGE("direct surface draw GL error=0x%x",error); return JNI_FALSE; }
    return JNI_TRUE;
}
JNIEXPORT void JNICALL Java_com_prts_client_live2d_Live2DView_nativeDispose(JNIEnv*,jobject) { dispose(); LOGI("model and GL resources released"); }
}
