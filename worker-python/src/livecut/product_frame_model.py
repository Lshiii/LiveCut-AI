"""Local Chinese-CLIP ranking for source-bound candidate frames."""
from __future__ import annotations

import hashlib
import os
import tempfile
from functools import lru_cache
from pathlib import Path

MODEL_ID = "Xenova/chinese-clip-vit-base-patch16@f26904860903e70e050b8f48255e5f48401816e9/int8"
MODEL_FILES = {
    "model_quantized.onnx": "a2c2849329eafb24100acec5a9421a39f8cb445296444c5c98855b69e5e7a751",
    "tokenizer.json": "7dfbf1966ebf99d471c3796e9b457329d2b2182b817e144f1e904b957745c839",
}
_SIZE = 224
_FEATURES = 512


class ProductFrameModelUnavailable(ValueError):
    """No verified local encoder is available. Never substitutes a synthetic score."""


def _model_dir() -> Path:
    configured = os.environ.get("LIVECUT_PRODUCT_FRAME_MODEL_DIR")
    return Path(configured) if configured else Path(__file__).resolve().parents[3] / "data/models/chinese-clip-vit-base-patch16"


def _cache_dir() -> Path:
    root = os.environ.get("LIVECUT_PRODUCT_FRAME_CACHE_DIR")
    default = Path(os.environ.get("LOCALAPPDATA") or os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache") / "livecut"
    return (Path(root) if root else default) / "chinese-clip-f269048-rgb-resize224-v2"


@lru_cache(maxsize=1)
def _encoder(directory: Path):
    try:
        import onnxruntime as ort
        from tokenizers import Tokenizer

        for name, expected in MODEL_FILES.items():
            with (directory / name).open("rb") as handle:
                if hashlib.file_digest(handle, "sha256").hexdigest() != expected:
                    raise ProductFrameModelUnavailable("Local Chinese-CLIP files failed checksum verification")
        options = ort.SessionOptions()
        options.intra_op_num_threads = 3
        options.inter_op_num_threads = 1
        session = ort.InferenceSession(str(directory / "model_quantized.onnx"), options,
                                       providers=["CPUExecutionProvider"])
        tokenizer = Tokenizer.from_file(str(directory / "tokenizer.json"))
        tokenizer.enable_truncation(max_length=52)
        return session, tokenizer
    except (ImportError, OSError) as exc:
        raise ProductFrameModelUnavailable("Install the product-frames extra and prepare the pinned Chinese-CLIP model") from exc


def _pixels(path: Path):
    import numpy as np
    from PIL import Image

    with Image.open(path) as image:
        rgb = image.convert("RGB").resize((_SIZE, _SIZE), Image.Resampling.BICUBIC)
    pixels = np.asarray(rgb, dtype=np.float32) / 255.0
    mean = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
    std = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
    return ((pixels - mean) / std).transpose(2, 0, 1)


def _unit(vector):
    import numpy as np

    vector = np.asarray(vector, dtype=np.float32)
    norm = np.linalg.norm(vector)
    if vector.shape != (_FEATURES,) or not np.isfinite(norm) or norm <= 0:
        raise ValueError("Invalid frame encoder output")
    return vector / norm


def rank_product_frames(label: str, images: list[Path]) -> list[float]:
    """Return cosine similarities in input order. The image index is reusable across labels."""
    if not isinstance(label, str) or not label.strip() or len(label) > 300 or not 1 <= len(images) <= 64:
        raise ValueError("Expected a product label and 1..64 source frames")
    try:
        import numpy as np
        import onnxruntime as ort
        from PIL import Image  # noqa: F401
    except ImportError as exc:
        raise ProductFrameModelUnavailable("Install LiveCut's product-frames extra") from exc

    session, tokenizer = _encoder(_model_dir().resolve())
    cache = _cache_dir()
    cache.mkdir(parents=True, exist_ok=True)
    ids = np.array([tokenizer.encode(label.strip()).ids], dtype=np.int64)
    feed = {"input_ids": ids, "attention_mask": np.ones_like(ids),
            "pixel_values": np.zeros((1, 3, _SIZE, _SIZE), dtype=np.float32)}
    options = ort.RunOptions()
    options.only_execute_path_to_fetches = True
    query = _unit(session.run(["text_embeds"], feed, options)[0][0])
    result = []
    for path in images:
        path = Path(path)
        with path.open("rb") as handle:
            digest = hashlib.file_digest(handle, "sha256").hexdigest()
        entry = cache / (digest + ".f32")
        try:
            with entry.open("rb") as handle:
                checksum = handle.read(32)
                payload = handle.read(4 * _FEATURES + 1)
            if len(payload) != 4 * _FEATURES or checksum != hashlib.sha256(bytes.fromhex(digest) + payload).digest():
                raise ValueError("Invalid cached frame embedding")
            vector = _unit(np.frombuffer(payload, dtype="<f4"))
        except (FileNotFoundError, ValueError):
            feed["pixel_values"] = _pixels(path)[None, ...]
            vector = _unit(session.run(["image_embeds"], feed, options)[0][0])
            with tempfile.NamedTemporaryFile(dir=cache, delete=False) as tmp:
                temporary = Path(tmp.name)
                payload = vector.astype("<f4").tobytes()
                tmp.write(hashlib.sha256(bytes.fromhex(digest) + payload).digest())
                tmp.write(payload)
            try:
                temporary.replace(entry)
            finally:
                temporary.unlink(missing_ok=True)
        result.append(float(np.clip(query @ vector, -1.0, 1.0)))
    return result
