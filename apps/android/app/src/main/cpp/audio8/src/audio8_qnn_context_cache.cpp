#include "audio8_qnn_context_cache.h"

#include <android/log.h>
#include <cerrno>
#include <chrono>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <map>
#include <sstream>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

namespace audio8 {
namespace {
namespace fs=std::filesystem;
using Clock=std::chrono::steady_clock;
constexpr char kLogTag[]="Audio8QnnCache";
constexpr char kContext[]="decoder_ctx.onnx";
constexpr char kManifest[]="manifest";
constexpr uintmax_t kMaxCacheBytes=1536ULL*1024*1024;
constexpr size_t kMaxManifestBytes=16384;

double ElapsedMs(Clock::time_point start) {
  return std::chrono::duration<double,std::milli>(Clock::now()-start).count();
}

class CacheLock {
 public:
  explicit CacheLock(const fs::path& path)
      :fd_(open(path.c_str(),O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600)) {
    if(fd_<0)return;
    int status;
    do {status=flock(fd_,LOCK_EX);} while(status<0 && errno==EINTR);
    if(status<0){close(fd_);fd_=-1;}
  }
  ~CacheLock(){if(fd_>=0)close(fd_);}
  explicit operator bool()const{return fd_>=0;}
  CacheLock(const CacheLock&)=delete;
  CacheLock& operator=(const CacheLock&)=delete;
 private:
  int fd_;
};

class CacheStage {
 public:
  explicit CacheStage(fs::path path):path_(std::move(path)) {}
  ~CacheStage(){if(!path_.empty()){std::error_code ec;fs::remove_all(path_,ec);}}
  void Published(){path_.clear();}
 private:
  fs::path path_;
};

bool SyncPath(const fs::path& path,int flags=0) {
  const int fd=open(path.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW|flags);
  if(fd<0)return false;
  int status;
  do {status=fsync(fd);} while(status<0 && errno==EINTR);
  close(fd);
  return status==0;
}

std::string BuildFingerprint() {
  std::string result;
  if(const auto* property=__system_property_find("ro.build.fingerprint")) {
    __system_property_read_callback(property,[](void* out,const char*,const char* value,uint32_t){
      *static_cast<std::string*>(out)=value;
    },&result);
  }
  return result;
}

bool AppendFileIdentity(std::ostringstream& out,const fs::path& path) {
  struct stat value{};
  if(stat(path.c_str(),&value)!=0 || !S_ISREG(value.st_mode) || value.st_size<=0) {
    out<<path.filename().string()<<":missing\n";
    return false;
  }
  out<<path.filename().string()<<':'<<value.st_dev<<':'<<value.st_ino<<':'<<value.st_size<<':'
     <<value.st_mtim.tv_sec<<':'<<value.st_mtim.tv_nsec<<':'
     <<value.st_ctim.tv_sec<<':'<<value.st_ctim.tv_nsec<<'\n';
  return true;
}

std::string CacheIdentity(const fs::path& graph,const QnnDecoderOptions& qnn) {
  const auto fingerprint=BuildFingerprint();
  if(fingerprint.empty())return {};
  std::ostringstream identity;
  identity<<"audio8-qnn-c12-context-v1\n"<<fingerprint<<'\n'<<OrtGetApiBase()->GetVersionString()<<'\n';
  const auto lib_dir=fs::path(qnn.plugin_path).parent_path();
  if(!AppendFileIdentity(identity,graph) ||
     !AppendFileIdentity(identity,graph.parent_path()/"weights_f32.data") ||
     !AppendFileIdentity(identity,qnn.plugin_path) ||
     !AppendFileIdentity(identity,qnn.backend_path) ||
     !AppendFileIdentity(identity,lib_dir/"libonnxruntime.so"))return {};
  for(const char* lib:{"libQnnHtpPrepare.so","libQnnSystem.so","libQnnHtpV73Stub.so"})
    AppendFileIdentity(identity,lib_dir/lib);
  AppendFileIdentity(identity,fs::path(qnn.dsp_directory)/"libQnnHtpV73Skel.so");
  return identity.str();
}

std::string ArtifactManifest(const fs::path& dir,uintmax_t& bytes) {
  bytes=0;
  std::error_code ec;
  if(!fs::is_directory(fs::symlink_status(dir,ec)) || ec)return {};
  std::map<std::string,uintmax_t> files;
  bool bin=false;
  for(fs::directory_iterator it(dir,ec),end;!ec && it!=end;it.increment(ec)) {
    const auto name=it->path().filename().string();
    if(!fs::is_regular_file(it->symlink_status(ec)) || ec)return {};
    if(name==kManifest)continue;
    if(name!=kContext && it->path().extension()!=".bin")return {};
    const auto size=it->file_size(ec);
    if(ec || size==0 || size>kMaxCacheBytes-bytes)return {};
    bytes+=size;
    files.emplace(name,size);
    bin=bin || name!=kContext;
  }
  if(ec || !bin || !files.count(kContext))return {};
  std::ostringstream result;
  for(const auto& file:files)result<<file.first<<':'<<file.second<<'\n';
  return result.str();
}

bool MatchManifest(const fs::path& path,const std::string& expected) {
  std::error_code ec;
  const auto bytes=fs::file_size(path,ec);
  if(ec || bytes!=expected.size() || bytes>kMaxManifestBytes)return false;
  std::ifstream stream(path,std::ios::binary);
  std::string actual(expected.size(),'\0');
  return static_cast<bool>(stream.read(actual.data(),actual.size())) && actual==expected;
}

bool PublishContext(const fs::path& root,const fs::path& stage,const fs::path& current,
                    const std::string& identity,uintmax_t& bytes) {
  const auto artifacts=ArtifactManifest(stage,bytes);
  if(artifacts.empty())return false;
  std::ofstream manifest(stage/kManifest,std::ios::binary|std::ios::trunc);
  manifest<<identity<<artifacts;
  manifest.close();
  if(!manifest)return false;
  std::error_code ec;
  for(fs::directory_iterator it(stage,ec),end;!ec && it!=end;it.increment(ec))
    if(!SyncPath(it->path()))return false;
  if(ec || !SyncPath(stage,O_DIRECTORY))return false;
  fs::remove_all(current,ec);
  if(ec)return false;
  fs::rename(stage,current,ec);
  if(ec)return false;
  // A directory sync protects publication across a device power interruption.
  if(!SyncPath(root,O_DIRECTORY))
    __android_log_print(ANDROID_LOG_WARN,kLogTag,"context directory sync failed");
  return true;
}
}

std::unique_ptr<Ort::Session> OpenQnnContextSession(
    Ort::Env& env,const Ort::SessionOptions& options,const std::string& graph,
    const QnnDecoderOptions& qnn,QnnContextCacheResult& result) {
  const auto open_model=[&](const fs::path& file,const Ort::SessionOptions& opts) {
    const auto begin=Clock::now();
    auto session=std::make_unique<Ort::Session>(env,file.c_str(),opts);
    result.session_load_ms=ElapsedMs(begin);
    return session;
  };
  const auto uncached=[&]() {
    __android_log_print(ANDROID_LOG_INFO,kLogTag,"cold compile started cache_status=%s",result.status);
    auto session=open_model(graph,options);
    result.cold_compile_ms=result.session_load_ms;
    __android_log_print(ANDROID_LOG_INFO,kLogTag,"cache_status=%s cold_compile_ms=%.2f session_load_ms=%.2f",
                        result.status,result.cold_compile_ms,result.session_load_ms);
    return session;
  };
  if(qnn.cache_directory.empty())return uncached();

  const fs::path root=qnn.cache_directory;
  std::error_code ec;
  fs::create_directories(root,ec);
  if(ec){result.status="unavailable";return uncached();}
  CacheLock lock(root/"lock");
  const auto identity=CacheIdentity(graph,qnn);
  if(!lock || identity.empty()){result.status="unavailable";return uncached();}
  const auto current=root/"current";
  const auto stage=root/"staging";
  fs::remove_all(stage,ec);
  const bool staging_clean=!ec;
  uintmax_t cache_bytes=0;
  const bool had_current=fs::exists(current,ec);
  const auto artifacts=ArtifactManifest(current,cache_bytes);
  result.status=had_current?"stale_recompiled":"miss_compiled";
  if(!artifacts.empty() && MatchManifest(current/kManifest,identity+artifacts)) {
    const auto begin=Clock::now();
    try {
      auto session=open_model(current/kContext,options);
      result.status="hit";
      result.context_cache_load_ms=result.session_load_ms;
      result.cache_bytes=cache_bytes;
      __android_log_print(ANDROID_LOG_INFO,kLogTag,"cache_status=hit cache_bytes=%llu session_load_ms=%.2f",
                          static_cast<unsigned long long>(cache_bytes),result.session_load_ms);
      return session;
    } catch(const Ort::Exception& error) {
      result.rejected_cache_ms=ElapsedMs(begin);
      result.status="rejected_rebuilt";
      __android_log_print(ANDROID_LOG_WARN,kLogTag,"cached context rejected after %.2f ms: %s",
                          result.rejected_cache_ms,error.what());
    }
  }

  fs::remove_all(current,ec);
  if(ec || !staging_clean || !fs::create_directory(stage,ec) || ec){result.status="unavailable";return uncached();}
  CacheStage stage_cleanup(stage);
  auto compile=options.Clone();
  compile.AddConfigEntry("ep.context_enable","1");
  compile.AddConfigEntry("ep.context_embed_mode","0");
  compile.AddConfigEntry("ep.context_file_path",(stage/kContext).c_str());
  __android_log_print(ANDROID_LOG_INFO,kLogTag,"cold compile started cache_status=%s",result.status);
  auto session=open_model(graph,compile);
  result.cold_compile_ms=result.session_load_ms;
  if(CacheIdentity(graph,qnn)==identity && PublishContext(root,stage,current,identity,cache_bytes)) {
    stage_cleanup.Published();
    result.cache_bytes=cache_bytes;
  } else {
    result.status="write_failed";
  }
  __android_log_print(ANDROID_LOG_INFO,kLogTag,"cache_status=%s cache_bytes=%llu cold_compile_ms=%.2f session_load_ms=%.2f",
                      result.status,static_cast<unsigned long long>(result.cache_bytes),result.cold_compile_ms,result.session_load_ms);
  return session;
}
}
