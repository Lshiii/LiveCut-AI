"""Download a pinned, data-only ONNX conversion for optional local retrieval.

Run explicitly; neither a browser upload nor a model request triggers downloads.
The original BGE model is BAAI/bge-small-zh-v1.5. This conversion is published
by Hugging Face's Xenova repository; provenance and actual SHA256 stay local.
"""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen

REPOSITORY = "Xenova/bge-small-zh-v1.5"
REVISION = "75c43b069aac4d136ba6bc1122f995fedcfd2781"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, default=Path("data/models/intent-bge-small"))
    args = parser.parse_args()
    root = args.directory.expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for remote, local in (("onnx/model_int8.onnx", "model_int8.onnx"), ("tokenizer.json", "tokenizer.json")):
        target = root / local
        if target.exists():
            raise SystemExit("Refusing to overwrite existing model files; choose another directory")
        request = Request(f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{remote}",
                          headers={"User-Agent": "LiveCut-AI-local-embedding-setup"})
        temporary = target.with_suffix(target.suffix + ".download")
        digest, size = hashlib.sha256(), 0
        try:
            with urlopen(request, timeout=90) as response, temporary.open("xb") as handle:
                while block := response.read(1024 * 1024):
                    size += len(block)
                    if size > 40 * 1024 * 1024:
                        raise ValueError("Embedding file exceeds the expected size bound")
                    digest.update(block)
                    handle.write(block)
            if size == 0:
                raise ValueError("Empty embedding download")
            temporary.replace(target)
            hashes[local] = digest.hexdigest()
        finally:
            temporary.unlink(missing_ok=True)
    manifest = {"format": "bge-cls-l2-v1", "repository": REPOSITORY, "revision": REVISION, "files": hashes}
    (root / "embedding-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"directory": str(root), "revision": REVISION, "files": hashes}))


if __name__ == "__main__":
    main()
