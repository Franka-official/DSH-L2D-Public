"""Provision the approved official Cubism Native5-r.4 SDK inputs locally.
Supply --archive or --sdk-dir after obtaining the SDK from the official site.
No downloads, hashes, compilation, private signing material or runtime actions.
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[1]
VERSION='5-r.4'
URL='https://cubism.live2d.com/sdk-native/bin/CubismSdkForNative-5-r.4.zip'
CORE='Core/dll/android/arm64-v8a/libLive2DCubismCore.so'
KEYS={'Framework/src/CubismFramework.cpp':10392,'Framework/src/CubismFramework.hpp':14570,
      'Framework/src/Rendering/OpenGL/CubismRenderer_OpenGLES2.cpp':31665,
      'Core/include/Live2DCubismCore.h':16238}

def included(relative):
    path=PurePosixPath(relative)
    if path.is_absolute() or '..' in path.parts:return False
    if relative==CORE or relative.startswith(('Framework/src/','Core/include/')):return True
    if len(path.parts)>2 or (len(path.parts)==2 and path.parts[0] not in ('Framework','Core')):return False
    return any(word in path.name.lower() for word in ('license','notice','redistributable','eula'))

def validate(directory,baseline):
    for name,size in KEYS.items():
        path=directory/name
        if not path.is_file() or path.stat().st_size!=size:raise RuntimeError('ExpectedCubism5r4KeySourceRequired:'+name)
    core=directory/CORE
    if not core.is_file():raise RuntimeError('LicensedArm64CoreLibraryRequired')
    with core.open('rb') as stream:header=stream.read(20)
    if header[:5]!=b'\x7fELF\x02' or header[18:20]!=b'\xb7\x00':raise RuntimeError('Arm64CoreElfRequired')
    licenses=[p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file() and
              any(word in p.name.lower() for word in ('license','notice','redistributable','eula'))]
    if not any(name.startswith('Framework/') for name in licenses):raise RuntimeError('OriginalFrameworkLicenseRequired')
    if not any(name.startswith('Core/') or '/' not in name for name in licenses):raise RuntimeError('OriginalCoreOrSdkCombinedLicenseRequired')
    compared={}
    if baseline:
        mapping={
            'Framework/src/CubismFramework.cpp':'app/src/main/cpp/cubism_framework/CubismFramework.cpp',
            'Framework/src/CubismFramework.hpp':'app/src/main/cpp/cubism_framework/CubismFramework.hpp',
            'Framework/src/Rendering/OpenGL/CubismRenderer_OpenGLES2.cpp':'app/src/main/cpp/cubism_framework/Rendering/OpenGL/CubismRenderer_OpenGLES2.cpp',
            'Core/include/Live2DCubismCore.h':'app/src/main/cpp/cubism_core/Live2DCubismCore.h',
            CORE:'app/src/main/jniLibs/arm64-v8a/libLive2DCubismCore.so'}
        for name,old in mapping.items():
            original=baseline/old
            if not original.is_file():raise RuntimeError('DeclaredBaselineInputMissing:'+old)
            compared[name]=(directory/name).read_bytes()==original.read_bytes()
        if not all(compared.values()):raise RuntimeError('OfficialSdkInputsDifferFromCurrentBaseline:'+json.dumps(compared))
    return dict(version=VERSION,official_download=URL,core_bytes=core.stat().st_size,
                key_source_bytes=KEYS,licenses_preserved=licenses,baseline_key_inputs_byte_equal=compared,
                licensed_core_is_vendor_binary=True,framework_and_bridge_will_compile_from_source=True,
                archive_size_not_inferred_from_headers=True,hashes_computed=False)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    inputs=parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--archive',type=Path)
    inputs.add_argument('--sdk-dir',type=Path)
    parser.add_argument('--destination',type=Path,default=ROOT/'.native-deps/cubism')
    parser.add_argument('--baseline-project',type=Path,help='Optional existing project; compare five key inputs byte-for-byte before committing')
    args=parser.parse_args();destination=args.destination.resolve()
    if destination.exists():
        if not (destination/'.dsh-cubism-inputs.json').is_file():raise RuntimeError('ExistingUnmanagedCubismInputsNotOverwritten')
        print(json.dumps(validate(destination,args.baseline_project),indent=2),flush=True);return
    staging=destination.with_name(destination.name+'.preparing')
    if staging.exists():raise RuntimeError('PartialCubismPreparationExistsInspectBeforeRetry')
    staging.mkdir(parents=True)
    if args.archive:
        with zipfile.ZipFile(args.archive) as archive:
            anchors=[name for name in archive.namelist() if name.endswith('Core/include/Live2DCubismCore.h')]
            if len(anchors)!=1:raise RuntimeError('UniqueOfficialSdkCoreHeaderRequired')
            prefix=anchors[0][:-len('Core/include/Live2DCubismCore.h')]
            for info in archive.infolist():
                if info.is_dir() or not info.filename.startswith(prefix):continue
                relative=info.filename[len(prefix):]
                if not included(relative):continue
                if (info.external_attr>>16)&0o170000==0o120000:raise RuntimeError('SdkSymlinkNotAccepted')
                target=staging/relative;target.parent.mkdir(parents=True,exist_ok=True)
                with archive.open(info) as source,target.open('wb') as output:shutil.copyfileobj(source,output)
    else:
        sdk=args.sdk_dir.resolve()
        paths=[]
        for directory in (sdk/'Framework/src',sdk/'Core/include'):
            if directory.is_dir():paths.extend(directory.rglob('*'))
        paths.append(sdk/CORE)
        for directory in (sdk,sdk/'Core',sdk/'Framework'):
            if directory.is_dir():paths.extend(path for path in directory.iterdir() if path.is_file())
        for path in paths:
            relative=path.relative_to(sdk).as_posix()
            if not included(relative) or not path.is_file():continue
            if path.is_symlink():raise RuntimeError('SdkSymlinkNotAccepted')
            target=staging/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)
    proof=validate(staging,args.baseline_project)
    proof['input_kind']='official_sdk_archive' if args.archive else 'supplied_official_sdk_directory'
    if args.archive:proof['archive_bytes']=args.archive.stat().st_size
    (staging/'.dsh-cubism-inputs.json').write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8')
    staging.rename(destination);proof['destination']=str(destination)
    print(json.dumps(proof,indent=2),flush=True)

if __name__=='__main__':main()
