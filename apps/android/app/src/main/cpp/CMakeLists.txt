cmake_minimum_required(VERSION 3.22.1)
project(prts_live2d LANGUAGES CXX)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "The current Android product supports arm64-v8a only")
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
get_filename_component(DSH_ANDROID_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)

# The Core is separately licensed. Framework and the app bridge compile from source.
set(DSH_CUBISM_FRAMEWORK_DIR "$ENV{DSH_CUBISM_FRAMEWORK_DIR}" CACHE PATH "Cubism Framework src directory")
set(DSH_CUBISM_CORE_INCLUDE_DIR "$ENV{DSH_CUBISM_CORE_INCLUDE_DIR}" CACHE PATH "Cubism Core include directory")
set(DSH_CUBISM_CORE_LIBRARY "$ENV{DSH_CUBISM_CORE_LIBRARY}" CACHE FILEPATH "Licensed ARM64 Cubism Core shared library")
if(NOT DSH_CUBISM_FRAMEWORK_DIR)
  set(DSH_CUBISM_FRAMEWORK_DIR "${DSH_ANDROID_ROOT}/.native-deps/cubism/Framework/src")
endif()
if(NOT DSH_CUBISM_CORE_INCLUDE_DIR)
  set(DSH_CUBISM_CORE_INCLUDE_DIR "${DSH_ANDROID_ROOT}/.native-deps/cubism/Core/include")
endif()
if(NOT DSH_CUBISM_CORE_LIBRARY)
  set(DSH_CUBISM_CORE_LIBRARY "${DSH_ANDROID_ROOT}/.native-deps/cubism/Core/dll/android/arm64-v8a/libLive2DCubismCore.so")
endif()
foreach(required "${DSH_CUBISM_FRAMEWORK_DIR}/CubismFramework.hpp" "${DSH_CUBISM_CORE_INCLUDE_DIR}/Live2DCubismCore.h" "${DSH_CUBISM_CORE_LIBRARY}")
  if(NOT EXISTS "${required}")
    message(FATAL_ERROR "Missing separately provisioned Cubism input: ${required}")
  endif()
endforeach()
file(GLOB_RECURSE FRAMEWORK_SRC CONFIGURE_DEPENDS "${DSH_CUBISM_FRAMEWORK_DIR}/*.cpp")
list(FILTER FRAMEWORK_SRC EXCLUDE REGEX "Rendering/(Vulkan|D3D9|D3D11|Metal)/")
add_library(prts_live2d SHARED prts_live2d.cpp ${FRAMEWORK_SRC})
target_include_directories(prts_live2d PRIVATE "${DSH_CUBISM_CORE_INCLUDE_DIR}" "${DSH_CUBISM_FRAMEWORK_DIR}")
target_compile_definitions(prts_live2d PRIVATE CSM_TARGET_ANDROID_ES2)
target_compile_options(prts_live2d PRIVATE -O2 -fexceptions -frtti)
add_library(cubism-core SHARED IMPORTED)
set_target_properties(cubism-core PROPERTIES IMPORTED_LOCATION "${DSH_CUBISM_CORE_LIBRARY}")
target_link_libraries(prts_live2d PRIVATE cubism-core GLESv2 log)


# Audio8 is isolated from the Qwen/MNN runtime. Weights remain in app-owned model storage.
set(DSH_AUDIO8_DEPS "${DSH_ANDROID_ROOT}/.native-deps/audio8" CACHE PATH "Audio8 ONNX Runtime Android inputs")
foreach(required "${DSH_AUDIO8_DEPS}/include/onnxruntime_cxx_api.h" "${DSH_AUDIO8_DEPS}/lib/arm64-v8a/libonnxruntime.so")
  if(NOT EXISTS "${required}")
    message(FATAL_ERROR "Missing Audio8 ONNX Runtime input: ${required}. Set DSH_AUDIO8_DEPS to its root.")
  endif()
endforeach()
add_library(audio8-ort SHARED IMPORTED)
set_target_properties(audio8-ort PROPERTIES IMPORTED_LOCATION "${DSH_AUDIO8_DEPS}/lib/arm64-v8a/libonnxruntime.so")

# Optional CPU-only MOSS backend shares the installed ORT library with Audio8.
set(DSH_MOSS_SENTENCEPIECE_SOURCE_DIR "" CACHE PATH "Pinned SentencePiece source (v0.2.1)")
if(DSH_MOSS_SENTENCEPIECE_SOURCE_DIR)
  if(NOT EXISTS "${DSH_MOSS_SENTENCEPIECE_SOURCE_DIR}/src/sentencepiece_processor.h")
    message(FATAL_ERROR "Missing SentencePiece source for MOSS-TTS-Nano")
  endif()
  set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
  set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
  set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
  set(SPM_USE_BUILTIN_PROTOBUF ON CACHE BOOL "" FORCE)
  set(CMAKE_POSITION_INDEPENDENT_CODE ON)
  add_subdirectory("${DSH_MOSS_SENTENCEPIECE_SOURCE_DIR}" sentencepiece EXCLUDE_FROM_ALL)
  # SentencePiece's bundled protobuf uses Android logging, including in its CLI tools.
  target_link_libraries(sentencepiece-static INTERFACE log)
  add_library(prts_moss_tts SHARED moss/moss_nano_jni.cpp)
  target_compile_features(prts_moss_tts PRIVATE cxx_std_17)
  target_include_directories(prts_moss_tts PRIVATE "${DSH_AUDIO8_DEPS}/include"
    "${DSH_MOSS_SENTENCEPIECE_SOURCE_DIR}/src")
  target_compile_definitions(prts_moss_tts PRIVATE ORT_API_MANUAL_INIT)
  target_compile_options(prts_moss_tts PRIVATE -O3 -fexceptions -frtti)
  target_link_libraries(prts_moss_tts PRIVATE audio8-ort sentencepiece-static)
endif()
add_library(prts_audio8_tts SHARED audio8/audio8_jni.cpp audio8/src/audio8_c_api.cpp audio8/src/audio8_engine.cpp audio8/src/npy_io.cpp audio8/src/audio8_incremental_codec.cpp audio8/src/audio8_scheduling.cpp audio8/src/audio8_runtime_metadata.cpp)
target_include_directories(prts_audio8_tts PRIVATE audio8/include "${DSH_AUDIO8_DEPS}/include")
target_compile_definitions(prts_audio8_tts PRIVATE ORT_API_MANUAL_INIT)
target_compile_options(prts_audio8_tts PRIVATE -O3 -fexceptions -frtti)

option(DSH_AUDIO8_QNN "Build the diagnostic Audio8 QNN HTP decoder" OFF)
if(DSH_AUDIO8_QNN)
  target_compile_definitions(prts_audio8_tts PRIVATE DSH_AUDIO8_QNN=1)
  target_sources(prts_audio8_tts PRIVATE audio8/src/audio8_qnn_context_cache.cpp)
endif()

option(DSH_AUDIO8_GPU "Build experimental Audio8 MNN OpenCL support" OFF)
target_link_libraries(prts_audio8_tts PRIVATE audio8-ort)
if(DSH_AUDIO8_GPU)
  set(DSH_AUDIO8_MNN_SOURCE_DIR "" CACHE PATH "Source tree matching the Android arm64 MNN archive")
  set(DSH_AUDIO8_MNN_ARCHIVE "" CACHE FILEPATH "Matching Android arm64 MNN archive")
  if(NOT EXISTS "${DSH_AUDIO8_MNN_SOURCE_DIR}/include/MNN/Interpreter.hpp" OR
     NOT EXISTS "${DSH_AUDIO8_MNN_ARCHIVE}")
    message(FATAL_ERROR "DSH_AUDIO8_GPU needs DSH_AUDIO8_MNN_SOURCE_DIR and DSH_AUDIO8_MNN_ARCHIVE")
  endif()
  # Keep the SDK definitions identical to the verified MNN Android archive.
  target_sources(prts_audio8_tts PRIVATE audio8/src/MnnIncrementalCodec.cpp audio8/src/audio8_mnn_metadata.cpp)
  target_include_directories(prts_audio8_tts PRIVATE
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/include"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/source"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/express"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/tools"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/codegen"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/schema/current"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/3rd_party"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/3rd_party/flatbuffers/include"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/3rd_party/half"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/3rd_party/imageHelper"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/3rd_party/OpenCLHeaders"
    "${DSH_AUDIO8_MNN_SOURCE_DIR}/source/backend/opencl/schema/current")
  target_compile_definitions(prts_audio8_tts PRIVATE
    "CL_HPP_TARGET_OPENCL_VERSION=110"
    "MNN_BUILD_FOR_ANDROID"
    "MNN_BUILD_STATIC_LIBS"
    "MNN_GATED_RMS_NORM"
    "MNN_LOW_MEMORY"
    "MNN_OPENCL_SVM_ENABLE"
    "MNN_SME2"
    "MNN_SUPPORT_DEPRECATED_OPV2"
    "MNN_SUPPORT_QUANT_EXTEND"
    "MNN_SUPPORT_TRANSFORMER_FUSE"
    "MNN_USE_LIB_WRAPPER"
    "MNN_USE_LOGCAT"
    "MNN_USE_NEON"
    "MNN_USE_THREAD_POOL"
    "ANDROID"
    "_FORTIFY_SOURCE=2"
    "NDEBUG")
  set_source_files_properties(audio8/src/MnnIncrementalCodec.cpp audio8/src/audio8_mnn_metadata.cpp PROPERTIES COMPILE_OPTIONS "-fno-rtti;-fvisibility=hidden;-fvisibility-inlines-hidden")
  target_link_libraries(prts_audio8_tts PRIVATE
    "-Wl,--whole-archive" "${DSH_AUDIO8_MNN_ARCHIVE}" "-Wl,--no-whole-archive")
else()
  target_sources(prts_audio8_tts PRIVATE audio8/src/audio8_mnn_disabled.cpp)
endif()
target_link_libraries(prts_audio8_tts PRIVATE log android dl atomic m)
target_link_options(prts_audio8_tts PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-Bsymbolic" "-Wl,--no-undefined")
