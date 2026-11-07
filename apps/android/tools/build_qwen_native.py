"""Compile fixed-source MNN + the current JNI adapter in a separate CMake tree."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
from bootstrap_native import ROOT, REV, validate

def run(args):
    print(json.dumps(dict(event='qwen_native_build_step',command=[str(a) for a in args])),flush=True)
    subprocess.run([str(a) for a in args],check=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',required=True,type=Path)
    parser.add_argument('--ndk',required=True,type=Path)
    parser.add_argument('--cmake',required=True,type=Path)
    parser.add_argument('--ninja',required=True,type=Path)
    parser.add_argument('--build-dir',required=True,type=Path)
    parser.add_argument('--output-dir',required=True,type=Path)
    parser.add_argument('--jobs',type=int,default=4)
    args=parser.parse_args();validate(args.source)
    if not 1<=args.jobs<=32:raise RuntimeError('BoundedBuildJobCountRequired')
    toolchain=args.ndk/'build/cmake/android.toolchain.cmake'
    for required in (toolchain,args.cmake,args.ninja):
        if not required.is_file():raise RuntimeError('MissingAndroidBuildTool:'+str(required))
    options=dict(CMAKE_BUILD_TYPE='Release',CMAKE_MAKE_PROGRAM=str(args.ninja.resolve()),
        CMAKE_TOOLCHAIN_FILE=str(toolchain.resolve()),ANDROID_ABI='arm64-v8a',ANDROID_PLATFORM='android-26',ANDROID_STL='c++_static',
        MNN_BUILD_SHARED_LIBS='OFF',MNN_SEP_BUILD='OFF',MNN_BUILD_TEST='OFF',MNN_BUILD_TOOLS='OFF',
        MNN_BUILD_BENCHMARK='OFF',MNN_BUILD_TRAIN='OFF',MNN_JNI='OFF',MNN_LOW_MEMORY='ON',
        MNN_SUPPORT_TRANSFORMER_FUSE='ON',MNN_BUILD_FOR_ANDROID_COMMAND='ON',MNN_BUILD_LLM='ON',
        MNN_BUILD_LLM_OMNI='ON',MNN_BUILD_AUDIO='ON',MNN_BUILD_OPENCV='ON',MNN_IMGCODECS='ON',
        MNN_OPENCL='ON',MNN_OPENGL='OFF',MNN_VULKAN='OFF',MNN_ARM82='ON',MNN_KLEIDIAI='OFF',MNN_OPENMP='OFF',
        MNN_LLM_BUILD_DEMO='OFF',LLM_SUPPORT_HTTP_RESOURCE='OFF',MNN_BUILD_CONVERTER='OFF',
        PRTS_QWEN_JNI_CMAKE=str((ROOT/'app/src/main/cpp/qwentts/QwenTtsJni.cmake').resolve()),
        CMAKE_LIBRARY_OUTPUT_DIRECTORY=str((args.build_dir/'out').resolve()))
    run([args.cmake,'-S',args.source.resolve(),'-B',args.build_dir.resolve(),'-G','Ninja']+['-D'+key+'='+value for key,value in options.items()])
    run([args.cmake,'--build',args.build_dir.resolve(),'--target','prts_qwen_tts','--parallel',str(args.jobs)])
    library=args.build_dir/'out/libprts_qwen_tts.so'
    if not library.is_file() or library.stat().st_size<1024*1024:raise RuntimeError('FreshQwenJniBuildOutputMissing')
    with library.open('rb') as stream:header=stream.read(20)
    if header[:5]!=b'\x7fELF\x02' or header[18:20]!=b'\xb7\x00':raise RuntimeError('Arm64ElfOutputRequired')
    output=args.output_dir/'arm64-v8a/libprts_qwen_tts.so';output.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(library,output)
    proof=dict(upstream_revision=REV,source=str(args.source.resolve()),jni_source=str(ROOT/'app/src/main/cpp/qwentts/qwen_tts_jni.cpp'),
        current_jni_and_mnn_compiled_from_source=True,legacy_binary_or_object_inputs_used=False,abi='arm64-v8a',api=26,
        output=str(output),bytes=output.stat().st_size,mixed_sampler=True,streaming_left_context_frames=25,device_validation_pending=True)
    (args.build_dir/'source-build-proof.json').write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(proof,indent=2),flush=True)

if __name__=='__main__':main()
