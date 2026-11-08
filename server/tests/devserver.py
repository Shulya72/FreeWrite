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


def argb(r, g, b):
    return (0xFF << 24 | r << 16 | g << 8 | b) - (1 << 32)


def demo(key):
    rnd = random.Random(5)
    items = []
    ids = [("i0", "Конструкция не принята", argb(190, 42, 42)), ("i1", "В оформлении", argb(230, 126, 34)),
           ("i2", "На подписи у Заказчика", argb(214, 170, 0)), ("i3", "Передано в ПТО", argb(41, 121, 255)),
           ("i4", "Скан в архиве", argb(35, 128, 58))]
    cons = [("c0", "Не начато", argb(140, 140, 140)), ("c1", "Опалубка", argb(230, 126, 34)),
            ("c2", "Армирование", argb(214, 170, 0)), ("c3", "Бетонирование", argb(41, 121, 255)),
            ("c4", "Принято", argb(35, 128, 58))]
    for sid, name, color in ids:
        items.append((sid, "status", json.dumps({"id": sid, "name": name, "color": color}, ensure_ascii=False)))
    for sid, name, color in cons:
        items.append((sid, "status", json.dumps({"id": sid, "name": name, "color": color, "g": "con"}, ensure_ascii=False)))
    items.append(("~order", "meta", json.dumps({"order": [x[0] for x in ids + cons]})))
    items.append(("~title", "meta", json.dumps({"title": "План ИГС по БК5 (проверка сайта)"}, ensure_ascii=False)))
    frames = [("f1", 0, 0, 2600, 1500, "КЖ 6.1.1"), ("f2", 2900, 0, 2000, 1500, "КЖ 6.1.2")]
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
    # Выноски и метки.
    items.append(("co1", "callout", json.dumps({"id": "co1", "x": 1800, "y": 900, "scale": 1.6, "title": "Плита ПМ-1",
                                               "sid": "c3", "leaders": [1200, 700]}, ensure_ascii=False)))
    items.append(("co2", "callout", json.dumps({"id": "co2", "x": 3500, "y": 700, "scale": 1.6, "title": "Стена Ст-4",
                                               "sid": "c2", "leaders": [3300, 500, 4200, 450]}, ensure_ascii=False)))
    items.append(("k1", "sticker", json.dumps({"id": "k1", "x": 1900, "y": 300, "sid": "i4", "scale": 1.5})))
    items.append(("t1", "text", json.dumps({"id": "t1", "x": 120, "y": 280, "size": 44, "text": "Секция 3 · приёмка",
                                            "color": argb(22, 34, 58)}, ensure_ascii=False)))
    items.append(("fl1", "fill", json.dumps({"id": "fl1", "pts": [300, 400, 900, 400, 900, 700, 300, 700],
                                             "color": argb(41, 121, 255), "alpha": 0.35})))
    push_all(key, items)


def from_file(key, path, images_dir):
    b = json.load(open(path, encoding="utf-8"))
    items = [("~title", "meta", json.dumps({"title": b.get("title", "Доска")}, ensure_ascii=False))]
    for st in b.get("statuses", []):
        items.append((st["id"], "status", json.dumps(st, ensure_ascii=False)))
    items.append(("~order", "meta", json.dumps({"order": [s["id"] for s in b.get("statuses", [])]})))
    kinds = {"strokes": "stroke", "texts": "text", "images": "image", "stickers": "sticker", "fills": "fill",
             "callouts": "callout", "frames": "frame", "rows": "row"}
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
