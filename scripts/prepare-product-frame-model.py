"""Explicitly install Chinese-CLIP data files for local product frame ranking."""
import argparse
import hashlib
import tempfile
from pathlib import Path
from urllib.request import urlopen

from livecut.product_frame_model import MODEL_FILES, MODEL_ID


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("data/models/chinese-clip-vit-base-patch16"))
    directory = parser.parse_args().directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    repo, revision = MODEL_ID.split("@")
    revision = revision.split("/")[0]
    for filename, expected in MODEL_FILES.items():
        target = directory / filename
        if target.exists():
            with target.open("rb") as handle:
                if hashlib.file_digest(handle, "sha256").hexdigest() == expected:
                    continue
            raise SystemExit(f"Checksum mismatch for existing {target}")
        remote = "onnx/" + filename if filename.endswith(".onnx") else filename
        url = f"https://huggingface.co/{repo}/resolve/{revision}/{remote}"
        digest = hashlib.sha256()
        with tempfile.NamedTemporaryFile(dir=directory, delete=False) as handle:
            temp = Path(handle.name)
        try:
            with urlopen(url, timeout=120) as response, temp.open("wb") as handle:
                while block := response.read(1024 * 1024):
                    digest.update(block)
                    handle.write(block)
            if digest.hexdigest() != expected:
                raise SystemExit(f"Invalid download for {filename}")
            temp.replace(target)
        finally:
            temp.unlink(missing_ok=True)
    print(directory)


if __name__ == "__main__":
    main()
