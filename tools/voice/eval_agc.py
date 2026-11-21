"""
Проверка автоусиления (0.32.1) на настоящей речи: как распознаётся «далёкий» голос с усилением и без.

Запускается в сборке GitHub (`.github/workflows/voice-eval.yml`): берёт модель из выпуска voice-model-1 и образцы речи
Сбера, делает из них «далёкий» голос — тише, с шумом комнаты и эхом — и сравнивает долю ошибок в словах (WER) с текстом,
который модель даёт на чистом звуке. Способ усиления здесь — тот же, что в приложении (`AutoGain` в Voice.kt).
"""

import json
import sys

import numpy as np
import onnxruntime as ort
import soundfile as sf

SR = 16000
F = 512


def load(path):
    x, sr = sf.read(path, dtype="float32", always_2d=True)
    x = x.mean(axis=1)
    assert sr == SR, sr
    return x


def session(path):
    o = ort.SessionOptions()
    o.intra_op_num_threads = 4
    return ort.InferenceSession(path, o, providers=["CPUExecutionProvider"])


def recognizer(model, tokens):
    s = session(model)
    pieces = [l.rstrip("\n").split("\t")[0] for l in open(tokens, encoding="utf-8")]
    blank = len(pieces)

    def rec(y):
        lab, fr = s.run(None, {"wav": y[None].astype(np.float32), "length": np.array([len(y)], np.int64)})
        out, prev = [], -1
        for t in lab[0][: int(fr[0])]:
            if t != blank and t != prev:
                out.append(int(t))
            prev = t
        return "".join(pieces[i] for i in out).replace("▁", " ").strip()

    return rec


def wer(ref, hyp):
    norm = lambda t: [w.strip(".,!?;:«»\"()").lower().replace("ё", "е") for w in t.split() if w.strip(".,!?;:«»\"()")]
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev = cur
    return d[len(h)] / max(1, len(r))


def auto_gain(y, target=0.1, max_gain=31.6, noise_ceil=None, win=10, smooth=5, lim=0.9):
    """Как AutoGain.apply в приложении: громкость по кадрам 32 мс, местный уровень речи — наибольший в ±win кадрах,
    усиление до target (не больше max_gain, не меньше 1), шум пауз — не выше noise_ceil, сглаживание, мягкий ограничитель."""
    n = len(y) // F
    if n == 0:
        return y.copy()
    r = np.array([np.sqrt((y[i * F:(i + 1) * F] ** 2).mean()) + 1e-7 for i in range(n)])
    loc = np.array([r[max(0, i - win):i + win + 1].max() for i in range(n)])
    g = np.clip(target / loc, 1.0, max_gain)
    if noise_ceil is not None:
        noise = np.percentile(r, 10)
        g = np.minimum(g, max(1.0, noise_ceil / noise))
    if smooth > 1:
        k = np.ones(smooth) / smooth
        g = np.convolve(np.pad(g, smooth // 2, mode="edge"), k, "valid")[:n]
    gs = np.interp(np.arange(len(y)), (np.arange(n) + 0.5) * F, g)
    z = y * gs
    a = np.abs(z)
    over = a > lim
    z[over] = np.sign(z[over]) * (lim + (1 - lim) * np.tanh((a[over] - lim) / (1 - lim)))
    return z.astype(np.float32)


def normalize(y, target=0.1, max_gain=31.6):
    n = len(y) // F
    r = np.array([np.sqrt((y[i * F:(i + 1) * F] ** 2).mean()) + 1e-7 for i in range(n)])
    lvl = np.percentile(r, 90)
    return np.clip(y * min(max_gain, max(1.0, target / lvl)), -1, 1).astype(np.float32)


def room(y, gain_db, noise_db, rt60, rng):
    """«Далёкий» голос: эхо комнаты (затухающий шум, RT60), тише на gain_db, шум комнаты noise_db дБ полной шкалы."""
    z = y.copy()
    if rt60 > 0:
        n = int(SR * rt60)
        t = np.arange(n) / SR
        ir = rng.normal(0, 1, n) * np.exp(-6.9 * t / rt60)
        ir[0] = 0
        ir = ir / np.sqrt((ir ** 2).sum()) * 0.7
        ir[0] = 0.5  # прямой звук
        z = np.convolve(z, ir)[: len(y)]
    z = z * 10 ** (gain_db / 20)
    # шум комнаты — «розовый» (сильнее на низких)
    w = rng.normal(0, 1, len(z))
    pink = np.cumsum(w)
    pink = pink - np.convolve(pink, np.ones(400) / 400, "same")
    pink = pink / (np.std(pink) + 1e-9) * 10 ** (noise_db / 20)
    return (z + pink).astype(np.float32)


def vad_probs(sess, y):
    st = np.zeros((2, 1, 128), np.float32)
    ctx = np.zeros((1, 64), np.float32)
    out = []
    for i in range(0, len(y) - F + 1, F):
        inp = np.concatenate([ctx, y[i:i + F][None]], 1).astype(np.float32)
        o, st = sess.run(None, {"input": inp, "state": st, "sr": np.array(SR, np.int64)})
        ctx = inp[:, -64:]
        out.append(float(o[0, 0]))
    return np.array(out)


def main():
    model, tokens, vad_path = sys.argv[1], sys.argv[2], sys.argv[3]
    rec = recognizer(model, tokens)
    vad = session(vad_path)
    pieces = []
    for p in sys.argv[4:]:
        x = load(p)
        step = 9 * SR
        for i in range(0, len(x) - 4 * SR, step):
            pieces.append(x[i:i + step])
    refs = [rec(p) for p in pieces]
    print("кусков:", len(pieces), "слов:", sum(len(r.split()) for r in refs))
    rms = np.mean([20 * np.log10(np.sqrt((p ** 2).mean())) for p in pieces])
    print(f"громкость образцов: {rms:.1f} дБ")
    rng = np.random.default_rng(7)
    methods = {
        "как есть": lambda y: y,
        "норм. по фразе": normalize,
        "усиление (окно 0,3 с)": lambda y: auto_gain(y),
        "усиление, шум ≤ -35": lambda y: auto_gain(y, noise_ceil=10 ** (-35 / 20)),
        "усиление (окно 1 с)": lambda y: auto_gain(y, win=31),
    }
    conds = [
        ("рядом", 0, -70, 0.0),
        ("1–2 м, тихо", -15, -62, 0.3),
        ("3 м", -25, -60, 0.45),
        ("5 м", -32, -58, 0.6),
        ("5 м, шумно", -32, -50, 0.6),
        ("далеко", -40, -60, 0.6),
        ("очень далеко", -48, -62, 0.8),
    ]
    # Детектор речи (Silero): какая доля кадров речи (по чистому звуку) находится в «далёком» голосе при порогах 0,5 / 0,35 / 0,25
    truth = [vad_probs(vad, p) > 0.5 for p in pieces]
    print("Детектор речи — найдено кадров речи (порог 0,5 / 0,35 / 0,25), как есть → с усилением:")
    for name, g, nz, rt in conds:
        rng2 = np.random.default_rng(11)
        ys = [room(p, g, nz, rt, rng2) for p in pieces]
        res = []
        for f in (lambda y: y, lambda y: auto_gain(y)):
            hits = {0.5: 0, 0.35: 0, 0.25: 0}
            false = 0
            total = 0
            for t, y in zip(truth, ys):
                pr = vad_probs(vad, f(y))
                m = min(len(pr), len(t))
                for th in hits:
                    hits[th] += int(((pr[:m] > th) & t[:m]).sum())
                false += int(((pr[:m] > 0.35) & ~t[:m]).sum())
                total += int(t[:m].sum())
            res.append(" / ".join(f"{hits[th] / max(1, total):.2f}" for th in (0.5, 0.35, 0.25)) + f" (лишних {false})")
        print(f"  {name:14s} {res[0]}  →  {res[1]}", flush=True)
    table = {}
    for name, g, nz, rt in conds:
        ys = [room(p, g, nz, rt, rng) for p in pieces]
        row = {}
        for mname, f in methods.items():
            row[mname] = round(float(np.mean([wer(r, rec(f(y))) for r, y in zip(refs, ys)])), 3)
        table[name] = row
        print(f"{name:14s} " + "  ".join(f"{m}: {v:.2f}" for m, v in row.items()), flush=True)
    print(json.dumps(table, ensure_ascii=False))


if __name__ == "__main__":
    main()
