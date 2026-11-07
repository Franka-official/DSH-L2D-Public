#include "audio8_mnn_metadata.h"
#include <MNN/Interpreter.hpp>
#include "core/Backend.hpp"
#include "backend/opencl/core/OpenCLBackend.hpp"
#include "backend/opencl/core/runtime/OpenCLRuntime.hpp"
#include <exception>
#include <iomanip>
#include <memory>
#include <sstream>
#include <string>

namespace {
std::string Q(const std::string& value){std::ostringstream out;out<<'"';for(unsigned char c:value){if(c=='"'||c=='\\')out<<'\\'<<c;else if(c<32)out<<"\\u"<<std::hex<<std::setw(4)<<std::setfill('0')<<static_cast<int>(c)<<std::dec;else out<<c;}out<<'"';return out.str();}
std::string Info(){
  MNN::BackendConfig backend_config;backend_config.precision=MNN::BackendConfig::Precision_High;
  MNN::ScheduleConfig config;config.type=MNN_FORWARD_OPENCL;config.backupType=MNN_FORWARD_CPU;
  config.numThread=MNN_GPU_TUNING_NONE|MNN_GPU_MEMORY_BUFFER;config.backendConfig=&backend_config;
  auto runtime=MNN::Interpreter::createRuntime({config});
  std::ostringstream out;out<<"{\"event\":\"audio8_mnn_runtime_info\",\"metadata_only\":true,\"model_sessions_created\":0,\"model_forward_calls\":0,\"ar_calls\":0,\"pcm_samples\":0,\"mnn_version\":"<<Q(MNN::getVersion())<<",\"requested_backend\":\"OPENCL\",\"requested_backend_type\":"<<static_cast<int>(MNN_FORWARD_OPENCL)<<",\"requested_gpu_mode\":"<<config.numThread<<",\"requested_precision\":\"high\",\"low_precision_enabled\":false,\"default_cpu_runtime_present\":"<<(runtime.second?"true":"false")<<",\"cpu_fallback_selected_for_model_execution\":false,\"runtime_map_types\":[";
  bool first=true;for(const auto& item:runtime.first){if(!first)out<<',';first=false;out<<static_cast<int>(item.first);}out<<']';
  const auto found=runtime.first.find(MNN_FORWARD_OPENCL);
  const bool has_opencl=found!=runtime.first.end()&&static_cast<bool>(found->second);
  out<<",\"runtime_map_contains_opencl\":"<<(has_opencl?"true":"false");
  if(!has_opencl){out<<",\"opencl_available\":false,\"status\":\"opencl_runtime_unavailable\",\"opencl_backend_created\":false}";return out.str();}
  std::unique_ptr<MNN::Backend> backend(found->second->onCreate(&backend_config));
  out<<",\"opencl_backend_created\":"<<(backend?"true":"false");
  if(!backend){out<<",\"opencl_available\":false,\"status\":\"opencl_backend_creation_failed\"}";return out.str();}
  const auto actual_type=backend->type();out<<",\"created_backend_type\":"<<static_cast<int>(actual_type);
  if(actual_type!=MNN_FORWARD_OPENCL){out<<",\"opencl_available\":false,\"status\":\"created_backend_is_not_opencl\"}";return out.str();}
  auto* cl=static_cast<MNN::OpenCL::OpenCLBackend*>(backend.get())->getOpenCLRuntime();
  if(!cl){out<<",\"opencl_available\":false,\"status\":\"opencl_runtime_pointer_unavailable\"}";return out.str();}
  const bool error=cl->isCreateError();out<<",\"opencl_context_create_error\":"<<(error?"true":"false")<<",\"device_name\":"<<Q(cl->getDeviceName())<<",\"fp16_hardware_supported\":"<<(cl->isSupportedFP16()?"true":"false")<<",\"device_compute_units\":"<<cl->deviceComputeUnits()<<",\"opencl_available\":"<<(error?"false":"true")<<",\"status\":"<<Q(error?"opencl_context_creation_failed":"opencl_runtime_ready_no_model_execution")<<",\"metadata_proves_model_gpu_execution\":false}";return out.str();
}
}
extern "C" __attribute__((visibility("default"))) const char* audio8_mnn_runtime_info_json(void){static thread_local std::string result;try{result=Info();}catch(const std::exception& e){result="{\"event\":\"audio8_mnn_runtime_info_error\",\"model_sessions_created\":0,\"model_forward_calls\":0,\"opencl_available\":false,\"message\":"+Q(e.what())+"}";}catch(...){result="{\"event\":\"audio8_mnn_runtime_info_error\",\"model_sessions_created\":0,\"model_forward_calls\":0,\"opencl_available\":false}";}return result.c_str();}
