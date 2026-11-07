"""Expand the published runtime/model packs for a source build.

Example: python3 tools/provision_assets.py --runtime runtime.zip
The default output is .runtime-assets, selected with DSH_ANDROID_ASSETS_DIR.
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[1]

def expand(archive,kind,destination):
    with zipfile.ZipFile(archive) as z:
        manifest=json.loads(z.read('DSH-ASSET-PACK.json'))
        if manifest.get('format')!=1 or manifest.get('kind')!=kind:
            raise RuntimeError('Wrong resource pack: '+str(archive))
        rows=manifest['files']; names=[row['path'] for row in rows]
        if len(set(names))!=len(names) or set(z.namelist())!=set(names)|{'DSH-ASSET-PACK.json'}:
            raise RuntimeError('Resource pack manifest differs from its entries')
        copied=reused=0
        dependency_generated=[]
        for row in rows:
            name=row['path']; relative=PurePosixPath(name)
            if relative.is_absolute() or '..' in relative.parts or '\\' in name:
                raise RuntimeError('Invalid resource path')
            info=z.getinfo(name)
            if info.file_size!=row['bytes']:raise RuntimeError('Resource size differs: '+name)
            checked_in=ROOT/'app/src/main/assets'/name
            output=destination/name
            if name=='licenses/mediapipe-third-party-NOTICE.txt':
                # Gradle retains the original NOTICE from the resolved public AAR.
                if output.is_file():
                    if output.read_bytes()!=z.read(name):raise RuntimeError('Unexpected existing generated notice')
                    output.unlink()
                dependency_generated.append(name);continue
            if checked_in.is_file():
                if checked_in.read_bytes()!=z.read(name):
                    raise RuntimeError('Pack conflicts with a source-controlled asset: '+name)
                reused+=info.file_size;continue
            if output.is_file() and output.stat().st_size==info.file_size:
                reused+=info.file_size;continue
            output.parent.mkdir(parents=True,exist_ok=True)
            temporary=output.with_name(output.name+'.writing')
            with z.open(info) as source,temporary.open('wb') as target:
                shutil.copyfileobj(source,target,1024*1024)
            if temporary.stat().st_size!=info.file_size:raise RuntimeError('Incomplete resource: '+name)
            temporary.replace(output);copied+=info.file_size
        return {'kind':kind,'files':len(rows),'copied_bytes':copied,'reused_bytes':reused,
                'prepared_from_public_dependency_by_gradle':dependency_generated}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'.runtime-assets')
    args=parser.parse_args();destination=args.output.resolve()
    destination.mkdir(parents=True,exist_ok=True)
    records=[expand(args.runtime,'runtime-assets',destination)]
    result={'assets_directory':str(destination),'packs':records}
    (destination/'../asset-provisioning.json').resolve().write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2),flush=True)

if __name__=='__main__':main()
