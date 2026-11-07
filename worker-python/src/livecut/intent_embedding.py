"""Explicit local BGE ONNX embeddings; loading never downloads or runs remote code."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path


class LocalIntentEmbedder:
    def __init__(self, directory: str | Path) -> None:
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer

        self.np = np
        root = Path(directory).expanduser().resolve()
        manifest = json.loads((root / "embedding-manifest.json").read_text(encoding="utf-8"))
        if manifest.get("format") != "bge-cls-l2-v1":
            raise ValueError("Unsupported intent embedding format")
        for name in ("model_int8.onnx", "tokenizer.json"):
            path = root / name
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != manifest["files"].get(name):
                raise ValueError("Intent embedding file hash mismatch")
        identity = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
        self.model_id = "local-bge-small-zh-v1.5:" + hashlib.sha256(identity).hexdigest()
        self.tokenizer = Tokenizer.from_file(str(root / "tokenizer.json"))
        self.tokenizer.enable_truncation(max_length=512)
        self.tokenizer.enable_padding()
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(root / "model_int8.onnx"), sess_options=options,
                                            providers=["CPUExecutionProvider"])

    def __call__(self, texts: list[str]) -> list[list[float]]:
        if not isinstance(texts, list) or any(not isinstance(text, str) or not text for text in texts):
            raise ValueError("Expected nonempty text inputs")
        result = []
        for offset in range(0, len(texts), 16):
            batch = self.tokenizer.encode_batch(texts[offset:offset + 16])
            data = {"input_ids": self.np.asarray([item.ids for item in batch], dtype=self.np.int64),
                    "attention_mask": self.np.asarray([item.attention_mask for item in batch], dtype=self.np.int64),
                    "token_type_ids": self.np.asarray([item.type_ids for item in batch], dtype=self.np.int64)}
            inputs = {item.name: data[item.name] for item in self.session.get_inputs()}
            vectors = self.session.run(None, inputs)[0]
            if vectors.ndim == 3:
                vectors = vectors[:, 0, :]
            if vectors.ndim != 2 or not self.np.isfinite(vectors).all():
                raise ValueError("Invalid local embedding output")
            norms = self.np.linalg.norm(vectors, axis=1, keepdims=True)
            if (norms <= 0).any():
                raise ValueError("Invalid zero embedding")
            result.extend((vectors / norms).tolist())
        return result
