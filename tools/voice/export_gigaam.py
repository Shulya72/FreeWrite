"""
Готовит модель распознавания речи GigaAM v3 e2e CTC (Сбер, лицензия MIT) для ViaDesk.

Запускается в сборке GitHub (`.github/workflows/voice-model.yml`), не на планшете.

Что делает:
1. Загружает модель `v3_e2e_ctc` из пакета GigaAM (текст сразу со знаками препинания и заглавными).
2. Собирает один граф ONNX «звук → номера знаков»: на входе отсчёты звука 16 кГц (float, −1…1) и их число,
   на выходе — номер самого вероятного знака в каждом кадре и число кадров. Лог-мел признаки считаются
   внутри графа (преобразование Фурье — свёрткой с готовыми ядрами), поэтому в приложении не нужно
   повторять обработку звука — меньше мест, где можно ошибиться.
3. Сжимает веса до 8 бит (только умножения матриц с постоянными весами; признаки и свёртки — как есть).
4. Пишет таблицу знаков `tokens.txt` (номер → кусок слова SentencePiece) и проверяет, что простая склейка
   кусков даёт тот же текст, что и SentencePiece.
5. Сверяет на образце: признаки графа против признаков GigaAM, текст оригинала против ONNX (полного и
   сжатого). Сильное расхождение — сборка останавливается.
6. Пишет `manifest.json` (файлы, размеры, SHA-256, тексты сверки).
"""

import argparse
import hashlib
import json
import math
import os
import random
import subprocess
import sys
import time

import numpy as np
import onnxruntime as ort
import torch
import torch.nn.functional as F
from onnxruntime.quantization import QuantType, quantize_dynamic
from torch import nn

import gigaam
from gigaam.preprocess import load_audio

MODEL = "v3_e2e_ctc"
SAMPLE_RATE = 16000


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class Wave2Labels(nn.Module):
    """Звук → лог-мел (как torchaudio.MelSpectrogram + log) → кодировщик → CTC → argmax."""

    def __init__(self, model):
        super().__init__()
        mel = model.preprocessor.featurizer[0]
        spec = mel.spectrogram
        self.n_fft = int(spec.n_fft)
        self.hop = int(spec.hop_length)
        self.win = int(spec.win_length)
        self.center = bool(spec.center)
        self.pad_mode = spec.pad_mode
        assert spec.power == 2.0, spec.power
        assert not spec.normalized, spec.normalized
        window = spec.window.detach().float()
        # torch.stft дополняет окно нулями до n_fft по центру.
        if self.win < self.n_fft:
            left = (self.n_fft - self.win) // 2
            window = F.pad(window, (left, self.n_fft - self.win - left))
        n_freq = self.n_fft // 2 + 1
        k = torch.arange(n_freq, dtype=torch.float64)[:, None]
        n = torch.arange(self.n_fft, dtype=torch.float64)[None, :]
        ang = 2.0 * math.pi * k * n / self.n_fft
        w = window.double()[None, :]
        cos = torch.cos(ang) * w
        sin = -torch.sin(ang) * w
        self.register_buffer("dft", torch.cat([cos, sin], 0).float().unsqueeze(1))  # (2F, 1, n_fft)
        fb = mel.mel_scale.fb.detach().float()  # (F, n_mels)
        self.register_buffer("melw", fb.t().contiguous().unsqueeze(-1))  # (n_mels, F, 1) — свёртка 1×1
        self.n_freq = n_freq
        self.encoder = model.encoder
        self.head = model.head

    def features(self, wav):
        x = wav.unsqueeze(1)  # (B, 1, N)
        if self.center:
            p = self.n_fft // 2
            x = F.pad(x, (p, p), mode=self.pad_mode)
        y = F.conv1d(x, self.dft, stride=self.hop)  # (B, 2F, T)
        re = y[:, : self.n_freq]
        im = y[:, self.n_freq :]
        power = re * re + im * im
        mel = F.conv1d(power, self.melw)  # (B, n_mels, T)
        return torch.log(mel.clamp(1e-9, 1e9))

    def feature_lengths(self, length):
        if self.center:
            return torch.div(length, self.hop, rounding_mode="floor") + 1
        return torch.div(length - self.win, self.hop, rounding_mode="floor") + 1

    def forward(self, wav, length):
        feats = self.features(wav)
        flen = self.feature_lengths(length)
        encoded, enc_len = self.encoder(feats, flen)
        log_probs = self.head(encoded)
        return log_probs.argmax(dim=-1), enc_len


def simple_decode(pieces, ids):
    """Как в приложении: склеить куски, «▁» → пробел, крайние пробелы убрать."""
    return "".join(pieces[i] for i in ids).replace("▁", " ").strip()


def ctc_collapse(labels, length, blank):
    out, prev = [], -1
    for t in range(int(length)):
        v = int(labels[t])
        if v != blank and v != prev:
            out.append(v)
        prev = v
    return out


def word_error_rate(ref, hyp):
    r, h = ref.lower().split(), hyp.lower().split()
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev = cur
    return d[len(h)] / max(1, len(r))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--audio", nargs="*", default=[], help="образцы речи для сверки")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    torch.set_num_threads(os.cpu_count() or 2)
    model = gigaam.load_model(MODEL, fp16_encoder=False, device="cpu")
    model.eval()
    print("config preprocessor:", dict(model.cfg.preprocessor))
    print("config encoder subsampling:", model.cfg.encoder.get("subsampling_factor"))

    tok = model.decoding.tokenizer
    assert not tok.charwise, "ожидался токенизатор SentencePiece"
    sp = tok.model
    vocab = len(tok)
    blank = vocab
    pieces = [sp.IdToPiece(i) for i in range(vocab)]
    special = [p for p in pieces if p.startswith("<") and p.endswith(">")]
    print(f"vocab={vocab} blank={blank} special={special[:20]}")

    # Склейка кусков должна совпадать с SentencePiece (кроме служебных <unk>, <s>, </s>).
    rng = random.Random(1)
    normal = [i for i, p in enumerate(pieces) if not (p.startswith("<") and p.endswith(">"))]
    bad = 0
    for _ in range(3000):
        ids = [rng.choice(normal) for _ in range(rng.randint(1, 25))]
        a, b = simple_decode(pieces, ids), sp.decode(ids).strip()
        if a != b:
            bad += 1
            if bad <= 5:
                print("decode mismatch:", repr(a), repr(b))
    assert bad == 0, f"склейка кусков расходится с SentencePiece: {bad}"

    normal_set = set(normal)
    with open(os.path.join(args.out, "tokens.txt"), "w", encoding="utf-8") as f:
        for i, p in enumerate(pieces):
            # Служебные куски в текст не попадают.
            text = p if i in normal_set else ""
            f.write(f"{text}\t{i}\n")

    net = Wave2Labels(model).eval()

    # Образцы для сверки.
    samples = []
    for path in args.audio:
        wav = load_audio(path)
        if wav.numel() > 24 * SAMPLE_RATE:
            wav = wav[: 24 * SAMPLE_RATE]
        samples.append((os.path.basename(path), wav))
    assert samples, "нет образцов речи для сверки"

    # 1) признаки графа против признаков GigaAM
    for name, wav in samples:
        with torch.no_grad():
            ref, _ = model.preprocessor(wav[None], torch.tensor([wav.numel()]))
            mine = net.features(wav[None])
        diff = (ref - mine).abs()
        print(f"features {name}: shape {tuple(ref.shape)} vs {tuple(mine.shape)}, max diff {diff.max():.5f}, mean {diff.mean():.6f}")
        assert ref.shape == mine.shape
        assert diff.mean() < 1e-3, "признаки расходятся"

    # 2) экспорт
    fp32 = os.path.join(args.out, "gigaam-fp32.onnx")
    example = (torch.randn(1, SAMPLE_RATE * 4) * 0.1, torch.tensor([SAMPLE_RATE * 4], dtype=torch.int64))
    ctx = model.encoder.onnx_export_mode() if hasattr(model.encoder, "onnx_export_mode") else None
    if ctx is not None:
        ctx.__enter__()
    try:
        with torch.no_grad():
            torch.onnx.export(
                net,
                example,
                fp32,
                input_names=["wav", "length"],
                output_names=["labels", "frames"],
                dynamic_axes={"wav": {0: "b", 1: "n"}, "length": {0: "b"}, "labels": {0: "b", 1: "t"}, "frames": {0: "b"}},
                opset_version=17,
                dynamo=False,
            )
    finally:
        if ctx is not None:
            ctx.__exit__(None, None, None)
    print("fp32 size MB:", os.path.getsize(fp32) / 1e6)

    int8 = os.path.join(args.out, "gigaam-v3-e2e-ctc-int8.onnx")
    quantize_dynamic(
        fp32,
        int8,
        op_types_to_quantize=["MatMul"],
        weight_type=QuantType.QInt8,
        extra_options={"MatMulConstBOnly": True},
    )
    print("int8 size MB:", os.path.getsize(int8) / 1e6)

    # 3) сверка текста
    def session(path):
        o = ort.SessionOptions()
        o.intra_op_num_threads = 4
        o.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        return ort.InferenceSession(path, o, providers=["CPUExecutionProvider"])

    s32, s8 = session(fp32), session(int8)
    checks = []
    worst = 0.0
    for name, wav in samples:
        with torch.no_grad():
            enc, enc_len = model.forward(wav[None], torch.tensor([wav.numel()]))
            ref_text = model.decoding.decode(model.head, enc, enc_len)[0][0].strip()
        feed = {"wav": wav[None].numpy().astype(np.float32), "length": np.array([wav.numel()], dtype=np.int64)}
        row = {"audio": name, "seconds": round(wav.numel() / SAMPLE_RATE, 2), "gigaam": ref_text}
        for label, s in (("fp32", s32), ("int8", s8)):
            t0 = time.time()
            labels, frames = s.run(None, feed)
            ms = (time.time() - t0) * 1000
            text = simple_decode(pieces, ctc_collapse(labels[0], frames[0], blank))
            row[label] = text
            row[label + "_ms"] = round(ms)
            row[label + "_wer"] = round(word_error_rate(ref_text, text), 3)
        worst = max(worst, row["fp32_wer"])
        print(json.dumps(row, ensure_ascii=False))
        checks.append(row)
    assert worst < 0.02, "ONNX fp32 расходится с оригиналом"
    assert max(r["int8_wer"] for r in checks) < 0.25, "сжатая модель слишком сильно расходится"

    os.remove(fp32)
    gigaam_commit = subprocess.run(
        ["git", "-C", os.path.dirname(os.path.dirname(gigaam.__file__)), "rev-parse", "HEAD"],
        capture_output=True, text=True,
    ).stdout.strip()
    files = {}
    for fn in ("gigaam-v3-e2e-ctc-int8.onnx", "tokens.txt"):
        p = os.path.join(args.out, fn)
        files[fn] = {"bytes": os.path.getsize(p), "sha256": sha256(p)}
    manifest = {
        "model": MODEL,
        "source": "https://github.com/salute-developers/GigaAM (MIT)",
        "gigaam_commit": gigaam_commit,
        "sample_rate": SAMPLE_RATE,
        "vocab": vocab,
        "blank": blank,
        "inputs": {"wav": "float32 [1, n], −1…1, 16 кГц моно", "length": "int64 [1]"},
        "outputs": {"labels": "int64 [1, t] — argmax по кадрам", "frames": "int64 [1] — число кадров"},
        "files": files,
        "checks": checks,
    }
    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(json.dumps(files, indent=2))


if __name__ == "__main__":
    sys.exit(main())
