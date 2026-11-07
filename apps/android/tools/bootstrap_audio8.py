#!/usr/bin/env python3
"""Prepare the pinned Android Audio8 inputs or write a model pack from local files."""

import argparse
import json
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import time
from urllib.request import Request, urlopen
import zipfile


MODEL_REPO = "Edge0/audio8-TTS-0.1B-ONNX-INT8"
MODEL_REV = "317c12d4e0da83847b594fcf8bd74bf2c76615ec"
MODEL_URL = f"https://huggingface.co/{MODEL_REPO}/resolve/{MODEL_REV}"
RUNTIME_REV = "1d64afcc2da56222b41ddcf53de7b8bc54be1fb6"
LICENSE_URL = f"https://raw.githubusercontent.com/Edge0-AI/Audio8_TTS/{RUNTIME_REV}/LICENSE"
ORT_SOURCE_URL = "https://raw.githubusercontent.com/microsoft/onnxruntime/v1.23.2"
ORT_ARCHIVE = "sherpa-onnx-v2.0.1-android.tar.bz2"
ORT_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/{ORT_ARCHIVE}"
ORT_ARCHIVE_SIZE = 45300379
ORT_LIBRARY = "lib/arm64-v8a/libonnxruntime.so"
CODEC_VARIANT = "incremental-causal-state-v1"
CODEC_GRAPH = "codec_incremental.onnx"
UPSTREAM_FILES = {
    "slow_ar_int8.onnx": 4820700,
    "slow_ar_int8.onnx.data": 133471232,
    "fast_ar_int8.onnx": 511306,
    "fast_ar_int8.onnx.data": 36718592,
    "codec_decoder_fp16.onnx": 594319,
    "codec_decoder_fp16.onnx.data": 260741440,
    "runtime_manifest.json": 1424,
    "config.json": 2651,
    "tokenizer/tokenizer.json": 5852397,
}
MODEL_FILES = {**UPSTREAM_FILES, CODEC_GRAPH: 552824}
ORT_HEADERS = {
    "onnxruntime_c_api.h": 332350,
    "onnxruntime_cxx_api.h": 144113,
    "onnxruntime_cxx_inline.h": 130952,
    "onnxruntime_ep_c_api.h": 45129,
    "onnxruntime_float16.h": 17860,
}
REFERENCE_FILES = ("reference.json", "reference.codes.i64.bin")
APACHE_LICENSE = Path(__file__).with_name("audio8-LICENSE.txt")
APACHE_NOTICE = Path(__file__).with_name("audio8-NOTICE.txt")


def matches(path, size):
    return path.is_file() and path.stat().st_size == size


def need(path, size):
    if not matches(path, size):
        raise ValueError(f"Expected {size} bytes at {path}")


def print_progress(label, received, total, start_size, started):
    elapsed = max(time.monotonic() - started, 0.001)
    rate = (received - start_size) / elapsed
    eta = f"{(total - received) / rate:.1f}s" if rate > 0 else "pending"
    print(f"{label}: {received}/{total} bytes, {rate / 2**20:.2f} MiB/s, ETA {eta}", flush=True)


def download(url, path, size):
    if matches(path, size):
        return
    if path.exists():
        raise ValueError(f"Unexpected existing file at {path}; move it aside before downloading")
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".part")
    offset = partial.stat().st_size if partial.exists() else 0
    if offset > size:
        raise ValueError(f"Partial file exceeds the locked size: {partial}")
    if offset < size:
        headers = {"Range": f"bytes={offset}-"} if offset else {}
        print(f"Download {url}\nDestination {path}\nResume at {offset}/{size} bytes", flush=True)
        started = time.monotonic()
        last_report = started
        received = offset
        with urlopen(Request(url, headers=headers), timeout=30) as response:
            if offset:
                expected = f"bytes {offset}-{size - 1}/{size}"
                if response.status != 206 or response.headers.get("Content-Range") != expected:
                    raise ValueError(f"Server did not confirm the resume offset for {partial}")
            elif response.status != 200:
                raise ValueError(f"Unexpected HTTP status for {url}: {response.status}")
            with partial.open("ab") as output:
                while block := response.read(1024 * 1024):
                    if received + len(block) > size:
                        raise ValueError(f"Download exceeded the locked size: {partial}")
                    output.write(block)
                    received += len(block)
                    if time.monotonic() - last_report >= 1 or received == size:
                        print_progress(path.name, received, size, offset, started)
                        last_report = time.monotonic()
    need(partial, size)
    partial.replace(path)


def install(source, destination, size):
    need(source, size)
    if matches(destination, size):
        return
    if destination.exists():
        raise ValueError(f"Unexpected existing file at {destination}; move it aside before preparing")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as out:
        partial = Path(out.name)
        try:
            with source.open("rb") as src:
                shutil.copyfileobj(src, out, length=1024 * 1024)
            out.close()
            need(partial, size)
            partial.replace(destination)
        finally:
            partial.unlink(missing_ok=True)


def check_arm64(path):
    with path.open("rb") as f:
        header = f.read(20)
    if header[:6] != b"\x7fELF\x02\x01" or header[18:20] != b"\xb7\x00":
        raise ValueError(f"Expected an AArch64 Android shared library at {path}")


def extract_ort_library(archive, destination):
    if destination.exists():
        check_arm64(destination)
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as tar:
        matches = [entry for entry in tar if entry.isfile() and entry.name.endswith("/arm64-v8a/libonnxruntime.so")]
        if len(matches) != 1:
            raise ValueError(f"Expected one arm64-v8a/libonnxruntime.so in {archive}")
        with tar.extractfile(matches[0]) as source:
            with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as out:
                partial = Path(out.name)
                try:
                    shutil.copyfileobj(source, out, length=1024 * 1024)
                    out.close()
                    need(partial, matches[0].size)
                    check_arm64(partial)
                    partial.replace(destination)
                finally:
                    partial.unlink(missing_ok=True)


def prepare_ort(root, cached, allow_download):
    if cached:
        for name in ORT_HEADERS:
            install(cached / "include" / name, root / "include" / name, ORT_HEADERS[name])
        src_library = cached / ORT_LIBRARY
        check_arm64(src_library)
        install(src_library, root / ORT_LIBRARY, src_library.stat().st_size)
        license_path = cached / "LICENSE"
        install(license_path, root / "LICENSE", license_path.stat().st_size)
    if allow_download:
        for name, size in ORT_HEADERS.items():
            download(f"{ORT_SOURCE_URL}/include/onnxruntime/core/session/{name}", root / "include" / name, size)
        download(f"{ORT_SOURCE_URL}/LICENSE", root / "LICENSE", 1073)
        library = root / ORT_LIBRARY
        if not library.is_file():
            archive = root / "downloads" / ORT_ARCHIVE
            download(ORT_URL, archive, ORT_ARCHIVE_SIZE)
            extract_ort_library(archive, library)
    for name, size in ORT_HEADERS.items():
        need(root / "include" / name, size)
    check_arm64(root / ORT_LIBRARY)
    if not (root / "LICENSE").is_file():
        raise ValueError("ONNX Runtime LICENSE is missing from the inputs")


def prepare_model(root, cached, graph, allow_download):
    for name, size in UPSTREAM_FILES.items():
        destination = root / "model" / name
        if cached and (cached / name).is_file():
            install(cached / name, destination, size)
        if allow_download:
            download(f"{MODEL_URL}/{name}", destination, size)
        need(destination, size)
    destination = root / "model" / CODEC_GRAPH
    source = graph if graph else cached / CODEC_GRAPH if cached else None
    if graph or (source and source.is_file()):
        install(source, destination, MODEL_FILES[CODEC_GRAPH])
    elif not destination.exists():
        from build_audio8_incremental_codec import build_codec

        build_codec(root / "model" / "codec_decoder_fp16.onnx", destination, MODEL_FILES[CODEC_GRAPH])
    need(destination, MODEL_FILES[CODEC_GRAPH])


def reference_entries(directory):
    metadata, codes = [directory / name for name in REFERENCE_FILES]
    if metadata.stat().st_size > 65536:
        raise ValueError("Reference metadata exceeds 64 KiB")
    reference = json.loads(metadata.read_text(encoding="utf-8"))
    shape = reference.get("shape")
    transcript = reference.get("reference_text")
    if (not isinstance(shape, list) or len(shape) != 2 or shape[0] != 10
            or type(shape[1]) is not int or not 0 < shape[1] < 2048
            or reference.get("dtype") != "int64_le"
            or reference.get("semantic_begin_id") != 65537
            or not isinstance(transcript, str) or not transcript.strip()):
        raise ValueError(f"Unexpected Audio8 reference format in {metadata}")
    need(codes, shape[1] * 10 * 8)
    with codes.open("rb") as source:
        while block := source.read(8192):
            if any(not 0 <= int.from_bytes(block[i:i + 8], "little", signed=True) < 4096
                   for i in range(0, len(block), 8)):
                raise ValueError(f"Invalid codebook value in {codes}")
    return {f"inputs/{p.name}": p.stat().st_size for p in (metadata, codes)}


def pack(root, destination, include_reference, reference_dir=None):
    files = {f"model/{name}": size for name, size in MODEL_FILES.items()}
    sources = {path: root / path for path in files}
    if include_reference:
        directory = reference_dir or root / "inputs"
        references = reference_entries(directory)
        files.update(references)
        sources.update({path: directory / Path(path).name for path in references})
    for path, size in files.items():
        need(sources[path], size)
    manifest = {
        "schema": "dsh-audio8-model-pack-v1",
        "revision": MODEL_REV,
        "source_hf_revision": MODEL_REV,
        "source_repo": MODEL_REPO,
        "codec_variant": CODEC_VARIANT,
        "derived_graph": {
            "path": f"model/{CODEC_GRAPH}",
            "origin": "locally-derived-from-pinned-source-graph",
            "source_hf_revision": MODEL_REV,
            "variant": CODEC_VARIANT,
            "shared_weights": "model/codec_decoder_fp16.onnx.data",
            "state_tensors": 36,
            "causal_attention_window": 128,
            "history_frame_replay": 0,
        },
        "files": [{"path": path, "bytes": size} for path, size in files.items()],
    }
    write_pack(destination, manifest, sources, model_license=True)


def pack_reference(directory, destination, voice):
    files = reference_entries(directory)
    manifest = {
        "schema": "dsh-audio8-reference-pack-v1",
        "revision": MODEL_REV,
        "source_hf_revision": MODEL_REV,
        "source_repo": MODEL_REPO,
        "codec_variant": CODEC_VARIANT,
        "reference_voice": voice,
        "files": [{"path": path, "bytes": size} for path, size in files.items()],
    }
    sources = {path: directory / Path(path).name for path in files}
    write_pack(destination, manifest, sources, model_license=False)


def write_pack(destination, manifest, sources, *, model_license):
    if destination.exists():
        raise ValueError(f"Output already exists: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as out:
        temporary = Path(out.name)
        out.close()
        try:
            total = sum(entry["bytes"] for entry in manifest["files"])
            completed = 0
            started = time.monotonic()
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as archive:
                archive.writestr("model-manifest.json", json.dumps(manifest, indent=2) + "\n")
                if model_license:
                    archive.write(APACHE_LICENSE, "LICENSE")
                    archive.write(APACHE_NOTICE, "NOTICE")
                for entry in manifest["files"]:
                    path, size = entry["path"], entry["bytes"]
                    archive.write(sources[path], path)
                    completed += size
                    print_progress("Pack", completed, total, 0, started)
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1] / ".native-deps" / "audio8")
    parser.add_argument("--model-cache", type=Path, help="Existing directory with the pinned upstream model files")
    parser.add_argument("--incremental-graph", type=Path, help="Existing codec_incremental.onnx with the pinned topology")
    parser.add_argument("--reference-dir", type=Path, help="Existing local reference.json and reference.codes.i64.bin")
    parser.add_argument("--ort-cache", type=Path, help="Existing Audio8 include/, lib/arm64-v8a/ and LICENSE tree")
    parser.add_argument("--prepare", action="store_true", help="Populate missing inputs from the selected local caches")
    parser.add_argument("--download", action="store_true", help="Prepare and explicitly download missing pinned inputs in the foreground")
    parser.add_argument("--skip-ort", action="store_true", help="Prepare model inputs only")
    pack_options = parser.add_mutually_exclusive_group()
    pack_options.add_argument("--pack", type=Path, help="Write a model ZIP from prepared inputs")
    pack_options.add_argument("--pack-reference", type=Path, help="Write a private reference-only ZIP")
    parser.add_argument("--reference-voice", choices=("default", "ellen", "whale"), default="default")
    parser.add_argument("--include-local-reference", action="store_true", help="Include a locally supplied reference in a private ZIP")
    args = parser.parse_args()
    if args.include_local_reference and not args.pack:
        parser.error("--include-local-reference requires --pack")
    if args.pack_reference and not args.reference_dir:
        parser.error("--pack-reference requires --reference-dir")
    if args.reference_voice != "default" and not args.pack_reference:
        parser.error("--reference-voice is used with --pack-reference")
    root = args.root.resolve()
    if args.prepare or args.download:
        prepare_model(root, args.model_cache, args.incremental_graph, args.download)
        if not args.skip_ort:
            prepare_ort(root, args.ort_cache, args.download)
        if args.reference_dir:
            for relative, size in reference_entries(args.reference_dir).items():
                install(args.reference_dir / Path(relative).name, root / relative, size)
    if args.pack:
        pack(root, args.pack.resolve(), args.include_local_reference, args.reference_dir)
    if args.pack_reference:
        pack_reference(args.reference_dir, args.pack_reference.resolve(), args.reference_voice)
    print(json.dumps({
        "root": str(root),
        "model_source": MODEL_URL,
        "model_license": LICENSE_URL,
        "ort_archive_source": ORT_URL,
        "ort_headers_source": ORT_SOURCE_URL,
        "missing_or_wrong_model_files": [name for name, size in MODEL_FILES.items() if not matches(root / "model" / name, size)],
        "reference_present": all((root / "inputs" / name).is_file() for name in REFERENCE_FILES),
        "ort_library_present": (root / ORT_LIBRARY).is_file(),
    }, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, ImportError, tarfile.TarError, zipfile.BadZipFile) as error:
        sys.exit(f"Audio8: {error}")
