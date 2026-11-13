"""
Местный сервер для проверки сайта в браузере (без облака): та же функция index.handler, база и хранилище
картинок — в памяти (как в test_api). Заполняет показательную доску и печатает коды.

    python3 server/tests/devserver.py [порт] [доска.json [папка_картинок]]

доска.json — доска FreeWrite (JSON как в приложении) — тогда выкладывается она (и картинки из папки).
Только для проверки на своём компьютере; в облако не попадает.
"""
import base64
import io
import json
import math
import os
import random
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)

import index as main  # noqa: E402
from test_api import Ctx, MemoryBlob, MemoryStore  # noqa: E402

BOARD = "a1b2c3d4-0000-4000-8000-0000000000aa"


def call(op, **kw):
    res = main.handler(dict(kw, op=op), Ctx())
    body = json.loads(res["body"])
    if res["statusCode"] != 200:
        raise RuntimeError("%s: %s" % (op, body))
    return body


class Seq:
    n = 0

    def rev(self):
        self.n += 1
        return "%013d.%04d.dev01" % (1762600000000 + self.n, 0)


def push_all(key, items):
    """items: [(id, kind, json_str)] — пачками по 1000."""
    s = Seq()
    for i in range(0, len(items), 1000):
        ch = [{"id": oid, "rev": s.rev(), "k": k, "d": d} for oid, k, d in items[i:i + 1000]]
        call("push", board=BOARD, key=key, device="dev01", changes=ch)


def put_image(key, name, data):
    parts = max(1, math.ceil(len(data) / main.CHUNK))
    for p in range(parts):
        chunk = data[p * main.CHUNK:(p + 1) * main.CHUNK]
        call("img_put", board=BOARD, key=key, file=name, part=p, parts=parts, size=len(data),
             data=base64.b64encode(chunk).decode())


def plan_png(w=1200, h=800):
    """Показательный чертёж (без сторонних библиотек): рамка листа, штамп, три помещения с дверями, колонна."""
    import struct
    import zlib
    px = bytearray(b"\xff" * (w * h))

    def box(l, t, r, b):
        for y in range(max(0, t), min(h, b)):
            px[y * w + max(0, l):y * w + min(w, r)] = b"\x00" * (min(w, r) - max(0, l))
    box(10, 10, 1190, 14); box(10, 786, 1190, 790); box(10, 10, 14, 790); box(1186, 10, 1190, 790)
    box(900, 700, 1186, 704); box(900, 700, 904, 786)
    box(100, 100, 1000, 106); box(100, 594, 1000, 600); box(100, 100, 106, 600); box(994, 100, 1000, 600)
    box(397, 100, 403, 330); box(397, 350, 403, 600); box(697, 100, 703, 330); box(697, 350, 703, 600)
    box(540, 330, 560, 350)
    raw = b"".join(b"\x00" + bytes(px[y * w:(y + 1) * w]) for y in range(h))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def argb(r, g, b):
    return (0xFF << 24 | r << 16 | g << 8 | b) - (1 << 32)


def demo(key):
    rnd = random.Random(5)
    items = []
    ids = [("i0", "Конструкция не выполнена", argb(138, 147, 163)), ("i5", "Не предоставлена", argb(198, 40, 40)),
           ("i1", "В работе", argb(31, 95, 209)), ("i9", "Сборка", argb(0, 131, 143)), ("i6", "На согласовании АН", argb(123, 63, 196)),
           ("i7", "Не согласована", argb(142, 27, 47)), ("i2", "На подписи у заказчика", argb(184, 138, 0)),
           ("i8", "Возврат на корректировку", argb(212, 100, 12)), ("i3", "Сдано в ПТО", argb(35, 128, 58))]
    cks = [("k0", "Не выполнено", argb(138, 147, 163)), ("k1", "Не предъявлено", argb(198, 40, 40)),
           ("k2", "Нет возможности предъявить", argb(212, 100, 12)), ("k3", "Предъявлено", argb(35, 128, 58))]
    dwgs = [("d0", "Не выполнено", argb(138, 147, 163)), ("d1", "Нет", argb(198, 40, 40)), ("d2", "Есть", argb(35, 128, 58))]
    cons = [("c0", "Не начато", argb(140, 140, 140)), ("c1", "Опалубка", argb(230, 126, 34)),
            ("c2", "Армирование", argb(214, 170, 0)), ("c3", "Бетонирование", argb(41, 121, 255)),
            ("c4", "Принято", argb(35, 128, 58))]
    for sid, name, color in ids:
        st = {"id": sid, "name": name, "color": color}
        if sid == "i3":
            st["done"] = 1
        items.append((sid, "status", json.dumps(st, ensure_ascii=False)))
    for group, lst in (("ck", cks), ("dwg", dwgs)):
        for sid, name, color in lst:
            st = {"id": sid, "name": name, "color": color, "g": group}
            if sid in ("k3", "d2"):
                st["done"] = 1
            items.append((sid, "status", json.dumps(st, ensure_ascii=False)))
    for sid, name, color in cons:
        items.append((sid, "status", json.dumps({"id": sid, "name": name, "color": color, "g": "con"}, ensure_ascii=False)))
    items.append(("~order", "meta", json.dumps({"order": [x[0] for x in ids + cons + cks + dwgs]})))
    items.append(("~title", "meta", json.dumps({"title": "План ИГС по БК5 (проверка сайта)"}, ensure_ascii=False)))
    frames = [("f1", 0, 0, 2600, 1500, "КЖ 6.1.1"), ("f2", 2900, 0, 2000, 1500, "КЖ 6.1.2"), ("f3", 0, 4700, 2700, 1500, "КЖ8")]
    for fid, x, y, w, h, t in frames:
        items.append((fid, "frame", json.dumps({"id": fid, "x": x, "y": y, "w": w, "h": h, "title": t}, ensure_ascii=False)))
    # Чертёж — картинка.
    try:
        from PIL import Image, ImageDraw
        im = Image.new("RGB", (1600, 900), "white")
        d = ImageDraw.Draw(im)
        for i in range(0, 1600, 80):
            d.line([(i, 0), (i, 900)], fill=(200, 210, 225), width=2)
        for i in range(0, 900, 80):
            d.line([(0, i), (1600, i)], fill=(200, 210, 225), width=2)
        d.rectangle([200, 150, 1400, 750], outline=(30, 30, 30), width=8)
        d.text((240, 180), "Схема КЖ 6.1.1 — лист 3", fill=(0, 0, 0))
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=88)
        put_image(key, "plan-1.jpg", buf.getvalue())
        items.append(("m1", "image", json.dumps({"id": "m1", "file": "plan-1.jpg", "x": 100, "y": 300, "w": 1600, "h": 900,
                                                 "pxW": 1600, "pxH": 900})))
    except ImportError:
        pass
    # Рукопись.
    for n in range(600):
        x, y = rnd.uniform(1750, 4800), rnd.uniform(200, 1400)
        pts = []
        for k in range(rnd.randint(5, 14)):
            pts += [round(x, 1), round(y, 1), 0.5]
            x += rnd.uniform(-2, 9)
            y += rnd.uniform(-5, 5)
        color = [argb(0, 0, 0), argb(255, 26, 64), argb(26, 140, 255)][n % 3]
        items.append(("s%d" % n, "stroke", json.dumps({"id": "s%d" % n, "kind": "pen", "color": color, "w": 2.5, "pts": pts})))
    # Строки ИД у захваток.
    for i in range(8):
        sid = ids[i % 5][0]
        items.append(("r%d" % i, "row", json.dumps({"id": "r%d" % i, "x": 120, "y": 40 + i * 30,
                                                    "scale": 0.7, "title": "Захватка %d — Опалубка" % (i + 1),
                                                    "ck": i % 2, "dwg": int(i % 3 == 0), "sid": sid}, ensure_ascii=False)))
    for i in range(3):
        items.append(("r1%d" % i, "row", json.dumps({"id": "r1%d" % i, "x": 3000, "y": 1200 + i * 32, "scale": 0.75,
                                                     "title": "Захватка %d — Армирование" % (i + 1), "ck": 1, "dwg": 1,
                                                     "sid": "i%d" % (2 + i)}, ensure_ascii=False)))
    # Таблица ИД КЖ 6.1.1 у чертежа (0.20).
    # Вид «Вертикальные элементы» (0.24): объём по схеме — Стены и Колонны, проект — для каждой части.
    items.append(("tb1", "table", json.dumps({"id": "tb1", "x": 100, "y": 1250, "scale": 1.6, "title": "Вертикальные элементы", "kind": "kj_vert",
                                              "plan": [{"w": "Бетон", "v": "280", "u": "м³", "p": "w"}, {"w": "Бетон", "v": "50", "u": "м³", "p": "c"},
                                                       {"w": "Опалубка", "v": "1200", "u": "м²", "p": "w"}, {"w": "Опалубка", "v": "250", "u": "м²", "p": "c"}],
                                              "sheets": [{"id": "sh1", "f": "plan-demo.png", "pw": 1200, "ph": 800,
                                                          "n": "План на отм. +3.300", "crop": [0.05, 0.08, 0.88, 0.86],
                                                          "seams": [[0.5833, 0.12, 0.5833, 0.76]]}]},
                                             ensure_ascii=False)))
    # Области захваток на листе плана (0.23): доли картинки целиком.
    for zid, z, (l, t, r, b) in [("zn1", "1", (103, 103, 400, 597)), ("zn2", "2", (400, 103, 700, 597)),
                                 ("zn3", "3", (700, 103, 997, 350)), ("zn4", "4", (700, 350, 997, 597))]:
        pts = [l / 1200, t / 800, r / 1200, t / 800, r / 1200, b / 800, l / 1200, b / 800]
        items.append((zid, "zone", json.dumps({"id": zid, "t": "tb1", "s": "sh1", "z": z, "color": argb(41, 121, 255),
                                               "pts": [round(v, 5) for v in pts], "e": 1})))
    # Разметка элементов (0.26): ещё не залитые — серые, без захватки.
    for i, (l, t, r, b) in enumerate([(120, 640, 300, 700), (330, 640, 520, 700), (560, 640, 760, 700), (800, 640, 990, 700)]):
        pts = [l / 1200, t / 800, r / 1200, t / 800, r / 1200, b / 800, l / 1200, b / 800]
        items.append(("el%d" % i, "zone", json.dumps({"id": "el%d" % i, "t": "tb1", "s": "sh1", "z": "", "color": argb(150, 152, 162),
                                                      "pts": [round(v, 5) for v in pts], "e": 1})))
    plan = [("1", "i3", "k3", "d2"), ("2", "i3", "k3", "d2"), ("2.1", "i2", "k3", "d2"), ("3", "i8", "k3", "d2"),
            ("4", "i6", "k3", "d1"), ("5", "i5", "k3", "d1"), ("6", "i1", "k1", "d1"), ("7", "i0", "k0", "d0")]
    o = 1
    for z, sid, ck, dwg in plan:
        for w in ("Опалубка", "Бетон"):
            rid = "rc%d" % o
            rec = {"id": rid, "t": "tb1", "o": o, "z": z, "c": "Стены и колонны", "w": w, "ck": ck, "dwg": dwg, "sid": sid,
                   "who": "Монолит-Строй", "d": "2026-0%d-%02d" % (7 + o // 10, 1 + o % 27)}
            if sid in ("i3", "i2"):
                rec["dc"] = "2026-10-20"
            if sid == "i3":
                rec["dp"] = "2026-10-27"
            if sid not in ("i0",):
                rec["vp"] = {"w": "%.1f" % ((180 + o * 1.7) if w == "Опалубка" else (40 + o * 0.6)),
                             "c": "%.1f" % ((24 + o * 0.2) if w == "Опалубка" else (5.4 + o * 0.05))}
            items.append((rid, "rec", json.dumps(rec, ensure_ascii=False)))
            o += 1
    # Плиты перекрытия (0.24) в разделе КЖ 6.1.2: объём — Плита и Балки.
    items.append(("tb3", "table", json.dumps({"id": "tb3", "x": 2950, "y": 60, "scale": 1.0, "title": "Плиты перекрытия", "kind": "kj_slab",
                                              "plan": [{"w": "Опалубка", "v": "1640", "u": "м²", "p": "p"}, {"w": "Опалубка", "v": "240", "u": "м²", "p": "b"},
                                                       {"w": "Бетон", "v": "395", "u": "м³", "p": "p"}]}, ensure_ascii=False)))
    for n, (z, w, vp, sid) in enumerate([("1", "Опалубка", {"p": "412.0", "b": "58.6"}, "i3"), ("1", "Бетон", {"p": "98.5", "b": "12.3"}, "i3"),
                                         ("2", "Опалубка", {"p": "405.3", "b": "61.2"}, "i1"), ("2", "Бетон", {}, "i0")]):
        rec = {"id": "rs%d" % n, "t": "tb3", "o": n + 1, "na": "АОСР 7.2.2-%03d" % (n + 1), "z": z, "w": w, "sid": sid,
               "ck": "k3" if sid != "i0" else "k0", "dwg": "d2" if sid != "i0" else "d0"}
        if vp:
            rec["vp"] = vp
        items.append(("rs%d" % n, "rec", json.dumps(rec, ensure_ascii=False)))
    # Лестницы КЖ8 (0.22.1): участок (LM1, LM2), захватка — этаж, тип конструкции — марш / площадка.
    items.append(("tb2", "table", json.dumps({"id": "tb2", "x": 100, "y": 4800, "scale": 1.6, "title": "Лестничные марши", "kind": "custom",
                                              "plan": [{"w": "Бетон", "v": "48", "u": "м³"}]}, ensure_ascii=False)))
    o2 = 1
    for u in ("LM1", "LM2"):
        for fl in ("2 этаж", "3 этаж"):
            for con in ("Марш", "Площадка межэтажная"):
                for w in ("Опалубка", "Бетон"):
                    rid = "rl%d" % o2
                    sid = "i3" if fl == "2 этаж" else ("i9" if u == "LM1" else "i1")
                    rec = {"id": rid, "t": "tb2", "o": o2, "na": "АОСР КЖ8-%03d" % o2, "u": u, "z": fl, "c": con, "w": w,
                           "ck": "k3" if sid != "i1" else "k1", "dwg": "d2" if sid != "i1" else "d1", "sid": sid, "who": "Монолит-Строй"}
                    if sid != "i1":
                        rec["v"] = "%.1f" % ((6.5 if con == "Марш" else 4.2) if w == "Опалубка" else (2.1 if con == "Марш" else 1.4))
                    items.append((rid, "rec", json.dumps(rec, ensure_ascii=False)))
                    o2 += 1
    items.append(("fl3", "fill", json.dumps({"id": "fl3", "pts": [1600, 400, 1900, 400, 1900, 700, 1600, 700],
                                             "color": argb(255, 152, 0), "alpha": 0.35, "lt": "tb2", "lu": "LM1", "lz": "3 этаж"},
                                            ensure_ascii=False)))
    items.append(("vd2", "vedom", json.dumps({"id": "vd2", "x": 2900, "y": 4800, "scale": 1.6, "kind": "cust", "num": "З-13",
                                              "to": "Технадзор заказчика"}, ensure_ascii=False)))
    for n, rid in enumerate(["rl5", "rl6", "rl7", "rl8"]):
        items.append(("vj%d" % n, "vitem", json.dumps({"id": "vj%d" % n, "v": "vd2", "r": rid, "o": n + 1}, ensure_ascii=False)))
    # Ведомость заказчику (0.21): передана, ответы по частям.
    items.append(("vd1", "vedom", json.dumps({"id": "vd1", "x": 2900, "y": 1650, "scale": 1.6, "kind": "cust", "num": "З-12",
                                              "to": "Технадзор заказчика", "given": "2026-11-05"}, ensure_ascii=False)))
    for n, (rid, res, rd) in enumerate([("rc1", "ok", "2026-11-06"), ("rc2", "ok", "2026-11-06"), ("rc7", "fix", "2026-11-09"),
                                        ("rc5", "", ""), ("rc6", "", "")]):
        it = {"id": "vi%d" % n, "v": "vd1", "r": rid, "o": n + 1}
        if res:
            it["res"] = res
            it["rd"] = rd
        items.append(("vi%d" % n, "vitem", json.dumps(it, ensure_ascii=False)))
    # Выноски и метки.
    items.append(("co1", "callout", json.dumps({"id": "co1", "x": 1800, "y": 900, "scale": 1.6, "title": "Плита ПМ-1",
                                               "sid": "c3", "leaders": [1200, 700]}, ensure_ascii=False)))
    items.append(("co2", "callout", json.dumps({"id": "co2", "x": 3500, "y": 700, "scale": 1.6, "title": "Стена Ст-4",
                                               "sid": "c2", "leaders": [3300, 500, 4200, 450]}, ensure_ascii=False)))
    items.append(("k1", "sticker", json.dumps({"id": "k1", "x": 1900, "y": 300, "sid": "i4", "scale": 1.5})))
    items.append(("t1", "text", json.dumps({"id": "t1", "x": 120, "y": 280, "size": 44, "text": "Секция 3 · приёмка",
                                            "color": argb(22, 34, 58)}, ensure_ascii=False)))
    items.append(("fl1", "fill", json.dumps({"id": "fl1", "pts": [300, 400, 900, 400, 900, 700, 300, 700],
                                             "color": argb(41, 121, 255), "alpha": 0.35, "lt": "tb1", "lz": "2"})))
    items.append(("fl2", "fill", json.dumps({"id": "fl2", "pts": [950, 400, 1500, 400, 1500, 700, 950, 700],
                                             "color": argb(46, 160, 67), "alpha": 0.35, "lt": "tb1", "lz": "3"})))
    push_all(key, items)
    put_image(key, "plan-demo.png", plan_png())


def from_file(key, path, images_dir):
    b = json.load(open(path, encoding="utf-8"))
    items = [("~title", "meta", json.dumps({"title": b.get("title", "Доска")}, ensure_ascii=False))]
    for st in b.get("statuses", []):
        items.append((st["id"], "status", json.dumps(st, ensure_ascii=False)))
    items.append(("~order", "meta", json.dumps({"order": [s["id"] for s in b.get("statuses", [])]})))
    kinds = {"strokes": "stroke", "texts": "text", "images": "image", "stickers": "sticker", "fills": "fill",
             "callouts": "callout", "frames": "frame", "rows": "row", "tables": "table", "recs": "rec",
             "vedoms": "vedom", "vitems": "vitem", "events": "event"}
    for name, k in kinds.items():
        for o in b.get(name, []):
            items.append((o["id"], k, json.dumps(o, ensure_ascii=False)))
    push_all(key, items)
    if images_dir:
        for o in b.get("images", []):
            f = os.path.join(images_dir, o["file"])
            if os.path.exists(f):
                put_image(key, o["file"], open(f, "rb").read())


class Handler(BaseHTTPRequestHandler):
    def _send(self, res):
        body = res["body"].encode("utf-8")
        self.send_response(res["statusCode"])
        for k, v in res.get("headers", {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
        self._send(main.handler({"httpMethod": "GET", "queryStringParameters": q}, Ctx()))

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(n).decode("utf-8")
        self._send(main.handler({"httpMethod": "POST", "body": body, "isBase64Encoded": False}, Ctx()))

    def log_message(self, *a):
        pass


def main_():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    main._store = MemoryStore()
    main._blob = MemoryBlob()
    c = call("create", board=BOARD, title="Доска", device="dev01")
    if len(sys.argv) > 2:
        from_file(c["key"], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    else:
        demo(c["key"])
    view = call("view_link", board=BOARD, key=c["key"])["code"]
    print("http://localhost:%d/  код доски: %s  только смотреть: %s" % (port, c["invite"], view), flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main_()
