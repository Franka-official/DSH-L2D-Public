#include "audio8_scheduling.h"
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <dirent.h>
#include <fstream>
#include <iomanip>
#include <iterator>
#include <sched.h>
#include <set>
#include <sstream>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
namespace audio8 {
namespace {
std::string Quoted(const std::string& text){std::ostringstream s;s<<'"';for(char c:text){if(c=='"'||c=='\\')s<<'\\'<<c;else if(c=='\n')s<<"\\n";else if(c=='\r')s<<"\\r";else if(static_cast<unsigned char>(c)>=32)s<<c;}s<<'"';return s.str();}
std::string Ints(const std::vector<int>& values){std::ostringstream s;s<<'[';for(size_t i=0;i<values.size();++i){if(i)s<<',';s<<values[i];}s<<']';return s.str();}
struct MaskCall{long result=-1;int error=0;std::vector<int> cpus;};
struct SetCall{long result=-1;int error=0;};
int SelfTid(){return static_cast<int>(syscall(SYS_gettid));}
bool FillMask(const std::vector<int>& cpus,cpu_set_t& mask){CPU_ZERO(&mask);if(cpus.empty())return false;for(int cpu:cpus){if(cpu<0||cpu>=CPU_SETSIZE)return false;CPU_SET(cpu,&mask);}return true;}
MaskCall ReadMask(int tid,bool raw){MaskCall call;cpu_set_t mask;CPU_ZERO(&mask);errno=0;call.result=raw?syscall(SYS_sched_getaffinity,tid,sizeof(mask),&mask):sched_getaffinity(tid,sizeof(mask),&mask);if(call.result<0){call.error=errno;return call;}for(int i=0;i<CPU_SETSIZE;++i)if(CPU_ISSET(i,&mask))call.cpus.push_back(i);return call;}
SetCall SetMask(const std::vector<int>& cpus){SetCall call;cpu_set_t mask;if(!FillMask(cpus,mask)){call.error=EINVAL;return call;}errno=0;call.result=syscall(SYS_sched_setaffinity,SelfTid(),sizeof(mask),&mask);if(call.result<0)call.error=errno;return call;}
std::string MaskJson(const MaskCall& call){std::ostringstream out;out<<"{\"return_value\":"<<call.result<<",\"errno\":"<<call.error<<",\"cpus\":"<<Ints(call.cpus)<<'}';return out.str();}
std::vector<int> ParseList(std::string text){std::vector<int> out;std::stringstream input(text);std::string part;try{while(std::getline(input,part,',')){auto dash=part.find('-');int lo=std::stoi(part),hi=dash==std::string::npos?lo:std::stoi(part.substr(dash+1));if(lo<0||hi>=CPU_SETSIZE||hi<lo)return {};for(int cpu=lo;cpu<=hi;++cpu)out.push_back(cpu);}}catch(...){return {};}std::sort(out.begin(),out.end());out.erase(std::unique(out.begin(),out.end()),out.end());return out;}
}
double CurrentThreadCpuSeconds(){timespec time{};if(clock_gettime(CLOCK_THREAD_CPUTIME_ID,&time)!=0)return 0;return time.tv_sec+time.tv_nsec*1e-9;}
ThreadObservation ObserveThread(int tid){ThreadObservation result;result.tid=tid?tid:SelfTid();const auto raw=ReadMask(result.tid,true),libc=ReadMask(result.tid,false);result.allowed=raw.cpus;result.raw_mask_return=raw.result;result.error=raw.error;result.libc_allowed=libc.cpus;result.libc_mask_return=libc.result;result.libc_mask_error=libc.error;errno=0;result.nice=getpriority(PRIO_PROCESS,result.tid);if(errno)result.error=errno;result.policy=sched_getscheduler(result.tid);
  std::ifstream stat("/proc/self/task/"+std::to_string(result.tid)+"/stat");std::string line;std::getline(stat,line);auto end=line.rfind(')'),begin=line.find('(');if(begin!=std::string::npos&&end!=std::string::npos){result.name=line.substr(begin+1,end-begin-1);std::istringstream words(line.substr(end+2));std::vector<std::string> fields;std::string value;while(words>>value)fields.push_back(value);try{if(fields.size()>36)result.cpu=std::stoi(fields[36]);if(fields.size()>12)result.cpu_s=(std::stod(fields[11])+std::stod(fields[12]))/sysconf(_SC_CLK_TCK);}catch(...){result.error=EINVAL;}}
  if(result.tid==syscall(SYS_gettid)){result.cpu=sched_getcpu();result.cpu_s=CurrentThreadCpuSeconds();}return result;}
std::string ThreadObservation::Json()const{std::ostringstream out;out<<std::setprecision(12)<<"{\"tid\":"<<tid<<",\"name\":"<<Quoted(name)<<",\"nice\":"<<nice<<",\"scheduler_policy\":"<<policy<<",\"last_cpu\":"<<cpu<<",\"thread_cpu_s\":"<<cpu_s<<",\"effective_allowed_cpus\":"<<Ints(allowed)<<",\"observation_errno\":"<<error<<",\"raw_getaffinity_return\":"<<raw_mask_return<<",\"libc_getaffinity_return\":"<<libc_mask_return<<",\"libc_getaffinity_errno\":"<<libc_mask_error<<",\"libc_allowed_cpus\":"<<Ints(libc_allowed)<<",\"getaffinity_disagreement\":"<<(allowed!=libc_allowed?"true":"false")<<'}';return out.str();}
std::vector<int> CurrentProcessThreads(){std::vector<int> out;DIR* directory=opendir("/proc/self/task");if(!directory)return out;while(auto* item=readdir(directory)){try{std::string value=item->d_name;if(!value.empty()&&std::all_of(value.begin(),value.end(),[](char c){return c>='0'&&c<='9';}))out.push_back(std::stoi(value));}catch(...){}}closedir(directory);std::sort(out.begin(),out.end());return out;}
std::string ObserveThreadsJson(const std::vector<int>& tids){std::ostringstream out;out<<'[';for(size_t i=0;i<tids.size();++i){if(i)out<<',';out<<ObserveThread(tids[i]).Json();}out<<']';return out.str();}
std::string CpuHistogramJson(const std::map<int,int64_t>& values){std::ostringstream out;out<<'{';bool first=true;for(const auto& item:values){if(!first)out<<',';first=false;out<<Quoted(std::to_string(item.first))<<':'<<item.second;}out<<'}';return out.str();}
std::shared_ptr<SchedulingProfile> DetectSchedulingProfile(bool enabled,int ar_threads,int codec_threads){auto out=std::make_shared<SchedulingProfile>();out->requested=enabled;if(!enabled)return out;if(ar_threads!=1||codec_threads!=4){out->reason="unsupported_thread_configuration";return out;}auto inherited=ObserveThread().allowed;std::ifstream online_file("/sys/devices/system/cpu/online");std::string line;std::getline(online_file,line);auto online=ParseList(line);if(inherited.empty()||online.empty()){out->reason="allowed_or_online_unavailable";return out;}std::set_intersection(inherited.begin(),inherited.end(),online.begin(),online.end(),std::back_inserter(out->allowed_online));std::map<int,std::vector<int>> groups;
  for(int cpu:out->allowed_online){std::ifstream file("/sys/devices/system/cpu/cpu"+std::to_string(cpu)+"/cpu_capacity");int capacity=0;if(!(file>>capacity)||capacity<=0){out->reason="cpu_capacity_unavailable";return out;}out->capacities[cpu]=capacity;groups[capacity].push_back(cpu);}
  // Conservative automatic topology: one unique prime, four equal-capacity
  // performance cores, and at least one lower-capacity core. Other layouts
  // remain under OS scheduling instead of guessing from model names/frequency.
  if(groups.size()!=3){out->reason="unrecognized_capacity_layout";return out;}auto it=groups.rbegin();out->ar_cpus=it->second;++it;out->codec_cpus=it->second;
  if(out->ar_cpus.size()!=1||out->codec_cpus.size()!=4){out->ar_cpus.clear();out->codec_cpus.clear();out->reason="unrecognized_capacity_layout";return out;}
  out->supported=true;out->reason="capacity_detected_1prime_4performance";return out;}
std::string SchedulingProfile::Json()const{std::ostringstream out;out<<"{\"requested_auto_capacity\":"<<(requested?"true":"false")<<",\"supported_capacity_layout\":"<<(supported?"true":"false")<<",\"enabled\":"<<(Enabled()?"true":"false")<<",\"reason\":"<<Quoted(reason)<<",\"application_errno\":"<<application_error.load()<<",\"application_verification_failed\":"<<(application_verification_failed.load()?"true":"false")<<",\"setaffinity_backend\":\"raw_syscall_actual_tid\",\"original_allowed_online_cpus\":"<<Ints(allowed_online)<<",\"ar_requested_cpus\":"<<Ints(ar_cpus)<<",\"codec_requested_cpus\":"<<Ints(codec_cpus)<<",\"capacities\":{";bool first=true;for(const auto& value:capacities){if(!first)out<<',';first=false;out<<Quoted(std::to_string(value.first))<<':'<<value.second;}out<<"},\"global_cpu_policy_modified\":false}";return out.str();}
AffinityGuard::AffinityGuard(std::shared_ptr<SchedulingProfile> profile,SchedulingRole role):profile_(std::move(profile)){before_=ObserveThread();effective_=before_;if(!profile_||!profile_->Enabled())return;requested_=role==SchedulingRole::Ar?profile_->ar_cpus:profile_->codec_cpus;if(before_.allowed.empty()){profile_->application_error.store(EINVAL);error_=EINVAL;return;}attempted_=true;const auto set=SetMask(requested_);apply_return_=set.result;error_=set.error;effective_=ObserveThread();validation_mismatch_=effective_.allowed!=requested_;applied_=error_==0&&!validation_mismatch_;if(!applied_){profile_->application_error.store(error_);profile_->application_verification_failed.store(validation_mismatch_);if(effective_.allowed!=before_.allowed){const auto restore=SetMask(before_.allowed);restore_return_=restore.result;restore_error_=restore.error;}}}
AffinityGuard::~AffinityGuard(){Restore();}
void AffinityGuard::Restore(){if(finished_)return;during_end_=ObserveThread();if(attempted_&&applied_){const auto restore=SetMask(before_.allowed);restore_return_=restore.result;restore_error_=restore.error;}after_=ObserveThread();finished_=true;}
std::string AffinityGuard::Json()const{std::ostringstream out;out<<"{\"attempted\":"<<(attempted_?"true":"false")<<",\"applied_and_observed\":"<<(applied_?"true":"false")<<",\"requested_cpus\":"<<Ints(requested_)<<",\"setaffinity_backend\":\"raw_syscall_actual_tid\",\"apply_return\":"<<apply_return_<<",\"apply_errno\":"<<error_<<",\"validation_mismatch\":"<<(validation_mismatch_?"true":"false")<<",\"restore_return\":"<<restore_return_<<",\"restore_errno\":"<<restore_error_<<",\"restored_observed\":"<<(finished_&&after_.allowed==before_.allowed?"true":"false")<<",\"before\":"<<before_.Json()<<",\"effective_after_apply\":"<<effective_.Json()<<",\"operation_end\":"<<during_end_.Json()<<",\"after_restore\":"<<after_.Json()<<'}';return out.str();}
std::string SelfAffinityProbeJson(){
  const int tid=SelfTid();const auto original=ReadMask(tid,true);const auto initial_libc=ReadMask(tid,false);
  struct RestoreOnExit{std::vector<int> cpus;~RestoreOnExit(){if(!cpus.empty())SetMask(cpus);}} restore_on_exit{original.cpus};
  const auto proc_mask=[&](){std::ifstream file("/proc/self/task/"+std::to_string(tid)+"/status");std::string line;while(std::getline(file,line))if(line.rfind("Cpus_allowed_list:",0)==0)return ParseList(line.substr(line.find(':')+1));return std::vector<int>{};};
  auto profile=DetectSchedulingProfile(true,1,4);std::vector<int> requested=profile->supported?profile->codec_cpus:std::vector<int>{};if(requested.empty()&&!original.cpus.empty())requested.push_back(original.cpus.back());
  std::ostringstream out;out<<"{\"event\":\"affinity_self_probe\",\"model_sessions_created\":0,\"target_frames_generated\":0,\"pcm_samples\":0,\"uid\":"<<getuid()<<",\"pid\":"<<getpid()<<",\"tid\":"<<tid<<",\"cpu_set_t_bytes\":"<<sizeof(cpu_set_t)<<",\"cpu_setsize\":"<<CPU_SETSIZE<<",\"original_raw\":"<<MaskJson(original)<<",\"original_libc\":"<<MaskJson(initial_libc)<<",\"requested_cpus\":"<<Ints(requested)<<",\"trials\":[";
  bool restored=true,raw_verified=false;int trials=0;
  if(!original.cpus.empty()&&!requested.empty())for(int kind=0;kind<3;++kind){
    cpu_set_t mask;FillMask(requested,mask);errno=0;long rc=-1;const char* name=kind==0?"libc_pid0":kind==1?"libc_actual_tid":"raw_actual_tid";
    if(kind==2)rc=syscall(SYS_sched_setaffinity,tid,sizeof(mask),&mask);else rc=sched_setaffinity(kind==0?0:tid,sizeof(mask),&mask);const int error=rc<0?errno:0;
    const auto raw=ReadMask(tid,true),libc=ReadMask(tid,false);const auto proc=proc_mask();
    const auto restore=SetMask(original.cpus);const auto after_raw=ReadMask(tid,true),after_libc=ReadMask(tid,false);const auto after_proc=proc_mask();
    const bool one_restored=restore.result==0&&after_raw.cpus==original.cpus&&after_proc==original.cpus;
    if(trials++)out<<',';out<<"{\"method\":"<<Quoted(name)<<",\"return_value\":"<<rc<<",\"errno\":"<<error<<",\"raw_after_apply\":"<<MaskJson(raw)<<",\"libc_after_apply\":"<<MaskJson(libc)<<",\"proc_after_apply_cpus\":"<<Ints(proc)<<",\"requested_observed_raw\":"<<(raw.cpus==requested?"true":"false")<<",\"restore_return\":"<<restore.result<<",\"restore_errno\":"<<restore.error<<",\"raw_after_restore\":"<<MaskJson(after_raw)<<",\"libc_after_restore\":"<<MaskJson(after_libc)<<",\"proc_after_restore_cpus\":"<<Ints(after_proc)<<",\"restored\":"<<(one_restored?"true":"false")<<'}';
    if(kind==2)raw_verified=rc==0&&error==0&&raw.cpus==requested&&proc==requested;
    restored=restored&&one_restored;if(!one_restored)break;
  }
  const auto final=ReadMask(tid,true);restored=restored&&final.cpus==original.cpus;
  out<<"],\"raw_self_set_verified\":"<<(raw_verified?"true":"false")<<",\"original_mask_restored\":"<<(restored?"true":"false")<<",\"final_raw\":"<<MaskJson(final)<<",\"passed\":"<<(raw_verified&&restored&&trials==3?"true":"false")<<",\"global_cpu_policy_modified\":false}";return out.str();
}
}
