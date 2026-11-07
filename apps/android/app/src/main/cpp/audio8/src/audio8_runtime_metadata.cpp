#include "audio8_c_api.h"
#include <onnxruntime_c_api.h>
#include <cstdint>
#include <cstdlib>
#include <dlfcn.h>
#include <exception>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>
#ifdef __ANDROID__
#include <android/NeuralNetworks.h>
#include <sys/system_properties.h>
#endif

namespace {
std::string Q(const std::string& value){std::ostringstream out;out<<'"';for(unsigned char c:value){if(c=='"'||c=='\\')out<<'\\'<<c;else if(c<32)out<<"\\u"<<std::hex<<std::setw(4)<<std::setfill('0')<<static_cast<int>(c)<<std::dec;else out<<c;}out<<'"';return out.str();}
std::string OrtStatusJson(const OrtApi* api,OrtStatus* status){if(!status)return "{\"ok\":true,\"code\":0,\"message\":null}";const int code=api->GetErrorCode(status);const char* message=api->GetErrorMessage(status);const std::string text=message?message:"";api->ReleaseStatus(status);return "{\"ok\":false,\"code\":"+std::to_string(code)+",\"message\":"+Q(text)+"}";}
struct Library{void* handle=nullptr;explicit Library(const char* name){handle=dlopen(name,RTLD_NOW|RTLD_LOCAL);}~Library(){if(handle)dlclose(handle);}Library(const Library&)=delete;Library& operator=(const Library&)=delete;};
// Verified against the official provider factory header. These flags only
// register an EP on a temporary SessionOptions; no inference Session is made.
// https://github.com/microsoft/onnxruntime/blob/main/include/onnxruntime/core/providers/nnapi/nnapi_provider_factory.h
constexpr uint32_t kNnapiFp16=0x001,kNnapiCpuDisabled=0x004;
using AppendNnapi=OrtStatus* (*)(OrtSessionOptions*,uint32_t);
std::string NnapiRegistration(const OrtApi* api){
  auto append=reinterpret_cast<AppendNnapi>(dlsym(RTLD_DEFAULT,"OrtSessionOptionsAppendExecutionProvider_Nnapi"));
  std::string source=append?"RTLD_DEFAULT":"not_found";Library ort("libonnxruntime.so");
  if(!append&&ort.handle){append=reinterpret_cast<AppendNnapi>(dlsym(ort.handle,"OrtSessionOptionsAppendExecutionProvider_Nnapi"));if(append)source="libonnxruntime.so";}
  std::ostringstream out;out<<"{\"symbol_present\":"<<(append?"true":"false")<<",\"symbol_source\":"<<Q(source)<<",\"flag_definitions\":{\"USE_FP16\":"<<kNnapiFp16<<",\"CPU_DISABLED\":"<<kNnapiCpuDisabled<<"},\"session_created\":false,\"inference_calls\":0,\"trials\":[";
  if(append)for(int i=0;i<2;++i){if(i)out<<',';const uint32_t flags=i?kNnapiFp16|kNnapiCpuDisabled:0;OrtSessionOptions* options=nullptr;OrtStatus* status=api->CreateSessionOptions(&options);const bool created=status==nullptr&&options!=nullptr;
    out<<"{\"flags\":"<<flags<<",\"flag_names\":"<<(i?"[\"USE_FP16\",\"CPU_DISABLED\"]":"[]")<<",\"session_options_created\":"<<(created?"true":"false")<<",\"create_options_status\":"<<OrtStatusJson(api,status)<<",\"append_invoked\":"<<(created?"true":"false");
    if(created){out<<",\"append_status\":"<<OrtStatusJson(api,append(options,flags));api->ReleaseSessionOptions(options);}else out<<",\"append_status\":null";
    out<<",\"session_created\":false}";
  }
  out<<"],\"registration_proves_accelerator_execution\":false}";return out.str();
}
int AndroidApi(){
#ifdef __ANDROID__
  char value[PROP_VALUE_MAX]{};return __system_property_get("ro.build.version.sdk",value)>0?std::atoi(value):0;
#else
  return 0;
#endif
}
std::string AndroidDevices(int sdk){
  std::ostringstream out;out<<"{\"android_api_level\":"<<sdk<<",\"minimum_device_query_api\":29,\"model_created\":false,\"compilation_created\":false,\"execution_created\":false";
#ifdef __ANDROID__
  if(sdk<29){out<<",\"queried\":false,\"reason\":\"android_api_below_29\",\"devices\":[]}";return out.str();}
  Library lib("libneuralnetworks.so");if(!lib.handle){const char* e=dlerror();out<<",\"queried\":false,\"library_loaded\":false,\"error\":"<<Q(e?e:"")<<",\"devices\":[]}";return out.str();}
  using Count=int(*)(uint32_t*);using Get=int(*)(uint32_t,ANeuralNetworksDevice**);using Text=int(*)(const ANeuralNetworksDevice*,const char**);using Type=int(*)(const ANeuralNetworksDevice*,int32_t*);using Feature=int(*)(const ANeuralNetworksDevice*,int64_t*);
  const auto count=reinterpret_cast<Count>(dlsym(lib.handle,"ANeuralNetworks_getDeviceCount"));const auto get=reinterpret_cast<Get>(dlsym(lib.handle,"ANeuralNetworks_getDevice"));const auto name=reinterpret_cast<Text>(dlsym(lib.handle,"ANeuralNetworksDevice_getName"));const auto version=reinterpret_cast<Text>(dlsym(lib.handle,"ANeuralNetworksDevice_getVersion"));const auto type=reinterpret_cast<Type>(dlsym(lib.handle,"ANeuralNetworksDevice_getType"));const auto feature=reinterpret_cast<Feature>(dlsym(lib.handle,"ANeuralNetworksDevice_getFeatureLevel"));
  out<<",\"library_loaded\":true,\"symbols_available\":{\"getDeviceCount\":"<<(count?"true":"false")<<",\"getDevice\":"<<(get?"true":"false")<<",\"getName\":"<<(name?"true":"false")<<",\"getVersion\":"<<(version?"true":"false")<<",\"getType\":"<<(type?"true":"false")<<",\"getFeatureLevel\":"<<(feature?"true":"false")<<'}';
  if(!count||!get||!name||!version||!type||!feature){out<<",\"queried\":false,\"reason\":\"required_symbol_unavailable\",\"devices\":[]}";return out.str();}
  uint32_t n=0;const int count_status=count(&n);out<<",\"queried\":true,\"device_count_status\":"<<count_status<<",\"device_count\":"<<n<<",\"devices\":[";
  if(count_status==ANEURALNETWORKS_NO_ERROR)for(uint32_t i=0;i<n;++i){if(i)out<<',';ANeuralNetworksDevice* device=nullptr;const int device_status=get(i,&device);out<<"{\"index\":"<<i<<",\"get_device_status\":"<<device_status;
    if(device_status==ANEURALNETWORKS_NO_ERROR&&device){const char* a=nullptr;const char* b=nullptr;int32_t t=ANEURALNETWORKS_DEVICE_UNKNOWN;int64_t f=-1;const int sa=name(device,&a),sb=version(device,&b),st=type(device,&t),sf=feature(device,&f);const char* type_name=t==ANEURALNETWORKS_DEVICE_CPU?"CPU":t==ANEURALNETWORKS_DEVICE_GPU?"GPU":t==ANEURALNETWORKS_DEVICE_ACCELERATOR?"ACCELERATOR":t==ANEURALNETWORKS_DEVICE_OTHER?"OTHER":"UNKNOWN";
      out<<",\"name_status\":"<<sa<<",\"name\":"<<(sa==0&&a?Q(a):"null")<<",\"version_status\":"<<sb<<",\"version\":"<<(sb==0&&b?Q(b):"null")<<",\"type_status\":"<<st<<",\"type\":"<<t<<",\"type_name\":"<<Q(type_name)<<",\"feature_level_status\":"<<sf<<",\"feature_level\":"<<f;}
    out<<'}';}
  out<<"]}";
#else
  out<<",\"queried\":false,\"reason\":\"not_android\",\"devices\":[]}";
#endif
  return out.str();
}
std::string RuntimeInfo(){
  const OrtApiBase* base=OrtGetApiBase();const OrtApi* api=base?base->GetApi(ORT_API_VERSION):nullptr;
  std::ostringstream out;out<<"{\"event\":\"audio8_runtime_info\",\"metadata_schema_version\":1,\"metadata_only\":true,\"model_sessions_created\":0,\"target_frames_generated\":0,\"pcm_samples\":0,\"inference_calls\":0,\"production_nnapi_enabled\":false,\"onnxruntime_version\":"<<Q(base&&base->GetVersionString()?base->GetVersionString():"unavailable")<<",\"ort_api_version_requested\":"<<ORT_API_VERSION<<",\"ort_api_available\":"<<(api?"true":"false");
  if(api){char** providers=nullptr;int n=0;OrtStatus* status=api->GetAvailableProviders(&providers,&n);const bool ok=status==nullptr;out<<",\"available_providers_status\":"<<OrtStatusJson(api,status)<<",\"available_providers\":[";if(ok&&providers)for(int i=0;i<n;++i){if(i)out<<',';out<<Q(providers[i]?providers[i]:"");}out<<']';if(providers)out<<",\"release_providers_status\":"<<OrtStatusJson(api,api->ReleaseAvailableProviders(providers,n));out<<",\"nnapi_registration\":"<<NnapiRegistration(api);}else out<<",\"available_providers\":[],\"nnapi_registration\":null";
  out<<",\"android_nnapi\":"<<AndroidDevices(AndroidApi())<<'}';return out.str();
}
}
extern "C" const char* audio8_runtime_info_json(void){static thread_local std::string result;try{result=RuntimeInfo();}catch(const std::exception& e){result="{\"event\":\"audio8_runtime_info_error\",\"model_sessions_created\":0,\"inference_calls\":0,\"message\":"+Q(e.what())+"}";}catch(...){result="{\"event\":\"audio8_runtime_info_error\",\"model_sessions_created\":0,\"inference_calls\":0}";}return result.c_str();}
