"""Fetch pinned MNN source, apply the reviewed product patch; no native binaries.
Use --git-cache for a local Git source cache. Network fetch is explicit without it.
Cubism and runtime assets are separately provisioned licensed inputs.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tarfile

ROOT=Path(__file__).resolve().parents[1]
REV='3b04761b4a8f0ab60dad259f94771f19d1264475'
REMOTE='https://github.com/alibaba/MNN.git'
PATCH=ROOT/'native/patches/mnn-qwen3-tts-product.patch'
MARKER='.dsh-product-source.json'

def run(args,cwd=None,capture=False):
    environment=os.environ.copy()
    if cwd:environment['GIT_CEILING_DIRECTORIES']=str(Path(cwd).parent.resolve())
    return subprocess.run([str(a) for a in args],cwd=cwd,check=True,text=True,
        env=environment,stdout=subprocess.PIPE if capture else None).stdout

def validate(source):
    marker=source/MARKER
    if not marker.is_file():raise RuntimeError('ManagedProductSourceMarkerRequired')
    receipt=json.loads(marker.read_text(encoding='utf-8'))
    if receipt.get('revision')!=REV:raise RuntimeError('PinnedMnnRevisionRequired')
    if receipt.get('source_kind')=='official_revision_archive':
        if receipt.get('archive_root')!='MNN-'+REV:raise RuntimeError('ExactOfficialRevisionArchiveRootRequired')
    elif run(['git','rev-parse','HEAD'],source,True).strip()!=REV:raise RuntimeError('PinnedMnnCommitRequired')
    run(['git','apply','--reverse','--check',PATCH],source)
    engine=(source/'transformers/llm/engine/CMakeLists.txt').read_text(encoding='utf-8')
    if 'include("${PRTS_QWEN_JNI_CMAKE}")' not in engine:raise RuntimeError('QwenJniProductHookRequired')

def unpack_source_archive(archive,staging):
    expected='MNN-'+REV
    staging.mkdir()
    with tarfile.open(archive,'r:*') as container:
        members=container.getmembers()
        for member in members:
            parts=Path(member.name).parts
            if not parts or parts[0]!=expected or '..' in parts or Path(member.name).is_absolute():
                raise RuntimeError('ExactOfficialRevisionArchiveRootRequired')
            if not (member.isdir() or member.isfile()):raise RuntimeError('NonRegularSourceArchiveEntry:'+member.name)
            target=staging.joinpath(*parts[1:])
            if member.isdir():target.mkdir(parents=True,exist_ok=True);continue
            target.parent.mkdir(parents=True,exist_ok=True)
            stream=container.extractfile(member)
            if stream is None:raise RuntimeError('SourceArchiveFileUnavailable')
            with stream,target.open('wb') as output:
                while True:
                    chunk=stream.read(1024*1024)
                    if not chunk:break
                    output.write(chunk)
            target.chmod(member.mode & 0o777)
    return dict(source_kind='official_revision_archive',archive_root=expected,
                archive_bytes=archive.stat().st_size,archive_name=archive.name,
                archive_origin='https://github.com/alibaba/MNN/archive/'+REV+'.tar.gz',
                revision_pin_basis='approved official archive root plus exact patch contexts; no whole-tree digest computed')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    inputs=parser.add_mutually_exclusive_group()
    inputs.add_argument('--git-cache',type=Path)
    inputs.add_argument('--source-archive',type=Path,help='Approved official MNN-'+REV+' source tar archive; never a build-output archive')
    parser.add_argument('--destination',type=Path,default=ROOT/'.native-deps'/('mnn-'+REV))
    args=parser.parse_args();destination=args.destination.resolve()
    if destination.exists():
        validate(destination);print(json.dumps(dict(source=str(destination),revision=REV,already_prepared=True)));return
    destination.parent.mkdir(parents=True,exist_ok=True)
    staging=destination.with_name(destination.name+'.preparing')
    if staging.exists():raise RuntimeError('PartialSourcePreparationExistsInspectBeforeRetry:'+str(staging))
    evidence=dict(source_kind='git_commit')
    if args.source_archive:
        evidence=unpack_source_archive(args.source_archive.resolve(),staging)
    elif args.git_cache:
        run(['git','clone','--no-checkout','--no-hardlinks',args.git_cache.resolve(),staging])
        run(['git','checkout','--detach',REV],staging)
    else:
        staging.mkdir();run(['git','init','--quiet'],staging)
        run(['git','remote','add','origin',REMOTE],staging)
        run(['git','fetch','--depth','1','origin',REV],staging)
        run(['git','checkout','--detach',REV],staging)
    run(['git','apply','--check',PATCH],staging);run(['git','apply',PATCH],staging)
    engine=staging/'transformers/llm/engine/CMakeLists.txt'
    with engine.open('a',encoding='utf-8',newline='\n') as stream:
        stream.write('\n# DSH product JNI; implementation lives in the Android repository.\nif(PRTS_QWEN_JNI_CMAKE)\n  include("${PRTS_QWEN_JNI_CMAKE}")\nendif()\n')
    (staging/MARKER).write_text(json.dumps(dict(revision=REV,official_remote=REMOTE,product_patch=str(PATCH.relative_to(ROOT)),
        all_mnn_and_jni_binaries_must_be_compiled=True,**evidence),indent=2)+'\n',encoding='utf-8')
    validate(staging);staging.rename(destination)
    print(json.dumps(dict(source=str(destination),revision=REV,prepared=True),indent=2),flush=True)

if __name__=='__main__':main()
