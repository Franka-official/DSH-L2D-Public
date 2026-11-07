#pragma once
#include <atomic>
#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>
namespace audio8 {
struct ThreadObservation {
  int tid=-1,nice=0,policy=-1,cpu=-1,error=0,libc_mask_error=0;
  long raw_mask_return=-1,libc_mask_return=-1;
  double cpu_s=0;
  std::vector<int> allowed,libc_allowed;
  std::string name;
  std::string Json()const;
};
struct SchedulingProfile {
  bool requested=false,supported=false;
  std::string reason="disabled";
  std::vector<int> allowed_online,ar_cpus,codec_cpus;
  std::map<int,int> capacities;
  mutable std::atomic<int> application_error{0};
  mutable std::atomic<bool> application_verification_failed{false};
  bool Enabled()const{return requested&&supported&&application_error.load()==0&&!application_verification_failed.load();}
  std::string Json()const;
};
std::shared_ptr<SchedulingProfile> DetectSchedulingProfile(bool enabled,int ar_threads,int codec_threads);
enum class SchedulingRole {Ar,Codec};
ThreadObservation ObserveThread(int tid=0);
double CurrentThreadCpuSeconds();
std::vector<int> CurrentProcessThreads();
std::string ObserveThreadsJson(const std::vector<int>& tids);
std::string CpuHistogramJson(const std::map<int,int64_t>& values);
// Explicit no-model diagnostic. Changes only the calling thread and restores
// its original raw syscall mask after every trial.
std::string SelfAffinityProbeJson();
class AffinityGuard {
 public:
  AffinityGuard(std::shared_ptr<SchedulingProfile>,SchedulingRole);
  ~AffinityGuard();
  AffinityGuard(const AffinityGuard&)=delete;
  AffinityGuard& operator=(const AffinityGuard&)=delete;
  void Restore();
  std::string Json()const;
 private:
  std::shared_ptr<SchedulingProfile> profile_;
  ThreadObservation before_,effective_,during_end_,after_;
  std::vector<int> requested_;
  bool attempted_=false,applied_=false,finished_=false,validation_mismatch_=false;
  int error_=0,restore_error_=0;
  long apply_return_=-1,restore_return_=-1;
};
}
