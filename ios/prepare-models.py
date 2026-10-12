"""Download and verify the Android-pinned offline speech voices for the iOS bundle."""
from pathlib import Path
import hashlib
import os
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent / "SchoolHelp" / "Models"
MODELS = {
    "kokoro-en": (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2",
        103248205,
        "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
    ),
    "piper-pl": (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pl_PL-gosia-medium-int8.tar.bz2",
        21109262,
        "72acac4c4b031725c41a61b3af0314a3d30e1ec2cd83ee410ea5f9e6d2d9d4fb",
    ),
}

ROOT.mkdir(parents=True, exist_ok=True)
for name, (url, expected_size, expected_hash) in MODELS.items():
    target = ROOT / name
    stamp = target / ".verified"
    if stamp.exists() and stamp.read_text() == expected_hash:
        print(f"{name}: verified")
        continue
    archive = ROOT / f"{name}.part"
    digest = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(url, timeout=90) as response, archive.open("wb") as output:
        while block := response.read(1024 * 1024):
            digest.update(block)
            output.write(block)
            total += len(block)
    if total != expected_size or digest.hexdigest() != expected_hash:
        archive.unlink(missing_ok=True)
        raise RuntimeError(f"{name}: download verification failed")
    staging = ROOT / f"{name}.staging"
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir()
    with tarfile.open(archive, "r:bz2") as package:
        for member in package:
            path = (staging / member.name).resolve()
            if not path.is_relative_to(staging.resolve()) or not (member.isfile() or member.isdir()):
                raise RuntimeError(f"{name}: unsafe archive entry")
        package.extractall(staging)
    archive.unlink(missing_ok=True)
    stamp_path = staging / ".verified"
    stamp_path.write_text(expected_hash)
    shutil.rmtree(target, ignore_errors=True)
    os.replace(staging, target)
    print(f"{name}: ready")
