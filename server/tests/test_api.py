"""
Проверка логики сервера без облака: база и хранилище — в памяти (те же правила, что у YDB).
Запросы YDB проверяет отдельный прогон в облаке (deploy/smoke.py) после выкладки.

Запуск: python3 -m unittest discover -s server/tests
"""
import base64
import hmac
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import index as main  # noqa: E402
from fw_errors import ApiError, forbidden, not_found  # noqa: E402


class MemoryStore:
    def __init__(self):
        self.boards = {}
        self.invites = {}
        self.objects = {}  # (board, id) -> row
        self.imgs = {}

    def _auth(self, board_id, key):
        b = self.boards.get(board_id)
        if b is None:
            raise not_found()
        if not hmac.compare_digest(b["secret"].encode(), key.encode()):
            raise forbidden()
        return b

    def get_board(self, board_id):
        b = self.boards.get(board_id)
        return dict(b) if b else None

    def count_boards(self):
        return len(self.boards)

    def find_invite(self, code):
        return self.invites.get(code)

    def create_board(self, board_id, secret, title, invite, owner, now):
        if board_id in self.boards:
            return False
        self.boards[board_id] = {"board_id": board_id, "secret": secret, "title": title, "invite": invite,
                                 "seq": 0, "n_objects": 0, "owner": owner}
        self.invites[invite] = board_id
        return True

    def push(self, board_id, key, changes, device, now, max_objects, author=""):
        b = self._auth(board_id, key)
        seq, count, out = b["seq"], b["n_objects"], []
        for c in changes:
            old = self.objects.get((board_id, c["id"]))
            if old is not None and c["rev"] <= old["rev"]:
                continue
            if old is None:
                count += 1
            seq += 1
            out.append(dict(c, seq=seq, author=author))
        if count > max_objects:
            raise ApiError(507, "full", "full")
        for r in out:
            self.objects[(board_id, r["id"])] = r
            if r["id"] == "~title" and not r["deleted"]:
                b["title"] = json.loads(r["body"])["title"]
        b["seq"], b["n_objects"] = seq, count
        return seq, len(out)

    def pull(self, board_id, key, since, limit):
        b = self._auth(board_id, key)
        rows = sorted((r for (bid, _), r in self.objects.items() if bid == board_id and r["seq"] > since),
                      key=lambda r: r["seq"])[:limit]
        return b["seq"], b["title"], rows

    def images(self, names):
        return {n: self.imgs[n] for n in names if n in self.imgs}

    def images_total(self):
        return sum(i["size"] for i in self.imgs.values())

    def put_image(self, name, board_id, parts, size, now):
        self.imgs[name] = {"parts": parts, "size": size, "board": board_id}

    def delete_board(self, board_id, key):
        b = self._auth(board_id, key)
        gone = [(n, i["parts"]) for n, i in self.imgs.items() if i["board"] == board_id]
        for n, _ in gone:
            del self.imgs[n]
        for k in [k for k in self.objects if k[0] == board_id]:
            del self.objects[k]
        del self.invites[b["invite"]]
        del self.boards[board_id]
        return gone


class MemoryBlob:
    def __init__(self):
        self.data = {}

    def put(self, key, data, token):
        assert token == "iam-token"
        self.data[key] = data

    def get(self, key, token):
        if key not in self.data:
            raise ApiError(404, "no_part", "нет")
        return self.data[key]

    def delete(self, key, token):
        self.data.pop(key, None)


class Ctx:
    token = {"access_token": "iam-token", "token_type": "Bearer"}


BOARD = "6f1c2a9e-0b7d-4c55-9a35-2f0d1e7b8c41"


def rev(ms, n=0, dev="vlad01"):
    return "%013d.%04d.%s" % (ms, n, dev)


class ApiTest(unittest.TestCase):
    def setUp(self):
        main._store = MemoryStore()
        main._blob = MemoryBlob()

    def call(self, op, http=True, **kw):
        req = dict(kw, op=op)
        if http:
            event = {"httpMethod": "POST", "body": json.dumps(req), "isBase64Encoded": False}
        else:
            event = req
        res = main.handler(event, Ctx())
        return res["statusCode"], json.loads(res["body"])

    def ok(self, op, **kw):
        status, body = self.call(op, **kw)
        self.assertEqual(200, status, body)
        return body

    def create(self, title="Захватки"):
        return self.ok("create", board=BOARD, title=title, device="vlad01")

    def test_ping_get_and_raw(self):
        res = main.handler({"httpMethod": "GET", "queryStringParameters": {}}, Ctx())
        self.assertEqual(200, res["statusCode"])
        self.assertTrue(json.loads(res["body"])["ok"])
        self.assertTrue(self.ok("ping", http=False)["ok"])
        # тело в base64 (так бывает у HTTP-функций)
        body = base64.b64encode(json.dumps({"op": "ping"}).encode()).decode()
        res = main.handler({"httpMethod": "POST", "body": body, "isBase64Encoded": True}, Ctx())
        self.assertEqual(200, res["statusCode"])

    def test_create_join_info(self):
        c = self.create()
        self.assertEqual(8, len(c["invite"]))
        # Тот же планшет выкладывает ещё раз (ответ потерялся) — тот же ключ и код.
        again = self.ok("create", board=BOARD, title="ещё раз", device="vlad01")
        self.assertEqual((c["key"], c["invite"]), (again["key"], again["invite"]))
        # Другой планшет — «уже есть», ключ не выдаётся.
        status, body = self.call("create", board=BOARD, title="чужой", device="kolya2")
        self.assertEqual(409, status)
        self.assertEqual("exists", body["error"])
        self.assertNotIn("key", body)
        status, body = self.call("create", board=BOARD, title="без устройства")
        self.assertEqual(409, status)
        # Код можно ввести маленькими буквами и с дефисом посередине.
        code = c["invite"].lower()[:4] + "-" + c["invite"].lower()[4:]
        j = self.ok("join", invite=code)
        self.assertEqual(BOARD, j["board"])
        self.assertEqual(c["key"], j["key"])
        self.assertEqual("Захватки", j["title"])
        status, body = self.call("join", invite="ZZZZZZZZ")
        self.assertEqual(404, status)
        self.assertIn("Код не найден", body["message"])
        info = self.ok("info", board=BOARD, key=c["key"])
        self.assertEqual(0, info["seq"])

    def test_wrong_key_and_unknown_board(self):
        self.create()
        status, body = self.call("pull", board=BOARD, key="чужой", since=0)
        self.assertEqual(403, status)
        status, body = self.call("pull", board="00000000-0000-0000-0000-000000000000", key="x", since=0)
        self.assertEqual(404, status)
        status, body = self.call("nothing")
        self.assertEqual(400, status)
        res = main.handler({"httpMethod": "POST", "body": "{не json"}, Ctx())
        self.assertEqual(400, res["statusCode"])

    def test_push_pull_last_write_wins(self):
        key = self.create()["key"]
        text = {"id": "t1", "rev": rev(1000), "k": "text", "d": '{"id":"t1","text":"Захватка 1"}'}
        stroke = {"id": "s1", "rev": rev(1000, 1), "k": "stroke", "d": '{"id":"s1","pts":[1,2,3]}'}
        title = {"id": "~title", "rev": rev(1000, 2), "k": "meta", "d": '{"title":"Секция 3"}'}
        r = self.ok("push", board=BOARD, key=key, device="vlad01", author="Владимир Шульмин", changes=[text, stroke, title])
        self.assertEqual((3, 3, 0), (r["seq"], r["applied"], r["ignored"]))
        # Повтор того же — ничего не меняет.
        r = self.ok("push", board=BOARD, key=key, changes=[text, stroke])
        self.assertEqual((3, 0, 2), (r["seq"], r["applied"], r["ignored"]))
        # Коллега прислал более старую правку — не принимается; более новую — принимается.
        old = dict(text, rev=rev(900, 0, "kolya2"), d='{"id":"t1","text":"старое"}')
        new = dict(text, rev=rev(2000, 0, "kolya2"), d='{"id":"t1","text":"Захватка 1 — сдано"}')
        r = self.ok("push", board=BOARD, key=key, changes=[old])
        self.assertEqual(0, r["applied"])
        r = self.ok("push", board=BOARD, key=key, changes=[new])
        self.assertEqual((4, 1), (r["seq"], r["applied"]))
        # Удаление штриха.
        r = self.ok("push", board=BOARD, key=key, changes=[{"id": "s1", "rev": rev(2100), "k": "stroke", "x": 1}])
        self.assertEqual(5, r["seq"])

        p = self.ok("pull", board=BOARD, key=key, since=0)
        self.assertEqual(5, p["seq"])
        self.assertEqual("Секция 3", p["title"])
        self.assertFalse(p["more"])
        self.assertEqual(5, p["next"])
        by = {c["id"]: c for c in p["changes"]}
        self.assertEqual(3, len(by))
        self.assertIn("сдано", by["t1"]["d"])
        self.assertEqual("Владимир Шульмин", by["~title"]["a"])   # автор правки
        self.assertNotIn("a", by["t1"])                          # правка без имени — без автора
        self.assertEqual(1, by["s1"]["x"])
        self.assertNotIn("d", by["s1"])
        self.assertEqual([3, 4, 5], [c["s"] for c in p["changes"]])
        # С курсора — только новое; на последнем — пусто.
        self.assertEqual(["s1"], [c["id"] for c in self.ok("pull", board=BOARD, key=key, since=4)["changes"]])
        self.assertEqual([], self.ok("pull", board=BOARD, key=key, since=5)["changes"])

    def test_same_id_twice_in_one_push_keeps_latest(self):
        key = self.create()["key"]
        a = {"id": "t1", "rev": rev(2000), "k": "text", "d": '{"v":2}'}
        b = {"id": "t1", "rev": rev(1000), "k": "text", "d": '{"v":1}'}
        r = self.ok("push", board=BOARD, key=key, changes=[a, b])
        self.assertEqual(1, r["applied"])
        p = self.ok("pull", board=BOARD, key=key, since=0)
        self.assertEqual('{"v":2}', p["changes"][0]["d"])

    def test_pull_pages(self):
        key = self.create()["key"]
        changes = [{"id": "o%04d" % i, "rev": rev(1000, i), "k": "stroke", "d": "x" * 10} for i in range(1200)]
        for i in range(0, 1200, 1000):
            self.ok("push", board=BOARD, key=key, changes=changes[i:i + 1000])
        got, since, rounds = [], 0, 0
        while True:
            p = self.ok("pull", board=BOARD, key=key, since=since)
            got += p["changes"]
            since = p["next"]
            rounds += 1
            if not p["more"]:
                break
        self.assertEqual(1200, len(got))
        self.assertEqual(3, rounds)  # по 500
        self.assertEqual(1200, len({c["id"] for c in got}))

    def test_pull_page_by_size(self):
        key = self.create()["key"]
        big = "y" * (400 * 1024)
        changes = [{"id": "b%d" % i, "rev": rev(1000, i), "k": "stroke", "d": big} for i in range(8)]
        self.ok("push", board=BOARD, key=key, changes=changes)
        p = self.ok("pull", board=BOARD, key=key, since=0)
        self.assertTrue(p["more"])
        self.assertLess(len(p["changes"]), 8)
        self.assertLessEqual(sum(len(c["d"]) for c in p["changes"]), main.MAX_PULL_BYTES)

    def test_bad_changes_rejected(self):
        key = self.create()["key"]
        bad = [
            [{"id": "t1", "rev": "вчера", "k": "text", "d": "{}"}],
            [{"id": "../x", "rev": rev(1), "k": "text", "d": "{}"}],
            [{"id": "t1", "rev": rev(1), "k": "<script>", "d": "{}"}],
            [{"id": "t1", "rev": rev(1), "k": "averyveryverylongkind", "d": "{}"}],
            [{"id": "t1", "rev": rev(1), "k": "text", "d": "z" * (main.MAX_BODY + 1)}],
            [{"id": "t%d" % i, "rev": rev(1), "k": "text", "d": "{}"} for i in range(main.MAX_CHANGES + 1)],
            "не список",
        ]
        for changes in bad:
            status, _ = self.call("push", board=BOARD, key=key, changes=changes)
            self.assertEqual(400, status)

    def test_new_kinds_from_newer_app_are_kept(self):
        # Раздел (0.14) и другие будущие виды: сервер не разбирает, только хранит и отдаёт.
        key = self.create()["key"]
        body = json.dumps({"id": "f1", "x": 0, "y": 0, "w": 800, "h": 600, "title": "Секция 3"}, ensure_ascii=False)
        self.ok("push", board=BOARD, key=key, device="vlad01", changes=[{"id": "f1", "rev": rev(5), "k": "frame", "d": body}])
        rows = self.ok("pull", board=BOARD, key=key, since=0)["changes"]
        self.assertEqual(["frame"], [r["k"] for r in rows])
        self.assertEqual("Секция 3", json.loads(rows[0]["d"])["title"])

    def test_images_in_parts(self):
        key = self.create()["key"]
        data = os.urandom(main.CHUNK + 1000)
        parts = [data[:main.CHUNK], data[main.CHUNK:]]
        self.assertEqual([], self.ok("img_has", board=BOARD, key=key, files=["plan-1.jpg"])["have"])
        for i, p in enumerate(parts):
            self.ok("img_put", board=BOARD, key=key, file="plan-1.jpg", part=i, parts=2, size=len(data),
                    data=base64.b64encode(p).decode())
        self.assertEqual(["plan-1.jpg"], self.ok("img_has", board=BOARD, key=key, files=["plan-1.jpg", "x.jpg"])["have"])
        # Повторная выгрузка — сразу «уже есть».
        r = self.ok("img_put", board=BOARD, key=key, file="plan-1.jpg", part=0, parts=2, size=len(data),
                    data=base64.b64encode(parts[0]).decode())
        self.assertTrue(r.get("exists"))
        got = b""
        for i in range(2):
            g = self.ok("img_get", board=BOARD, key=key, file="plan-1.jpg", part=i)
            self.assertEqual(2, g["parts"])
            got += base64.b64decode(g["data"])
        self.assertEqual(data, got)
        status, _ = self.call("img_get", board=BOARD, key=key, file="nope.jpg", part=0)
        self.assertEqual(404, status)
        status, _ = self.call("img_put", board=BOARD, key=key, file="../../etc", part=0, parts=1, size=1, data="AA==")
        self.assertEqual(400, status)
        status, _ = self.call("img_put", board=BOARD, key=key, file="big.jpg", part=0, parts=1, size=10,
                              data=base64.b64encode(os.urandom(main.CHUNK + 1)).decode())
        self.assertEqual(400, status)

    def test_images_quota(self):
        key = self.create()["key"]
        old = main.MAX_IMAGE_BYTES
        main.MAX_IMAGE_BYTES = 1000
        try:
            status, body = self.call("img_put", board=BOARD, key=key, file="a.jpg", part=0, parts=1, size=2000,
                                     data=base64.b64encode(b"abc").decode())
            self.assertEqual(507, status)
            self.assertIn("нет места", body["message"])
        finally:
            main.MAX_IMAGE_BYTES = old

    def test_delete_board_removes_images(self):
        key = self.create()["key"]
        self.ok("img_put", board=BOARD, key=key, file="p.jpg", part=0, parts=1, size=3,
                data=base64.b64encode(b"abc").decode())
        self.ok("push", board=BOARD, key=key, changes=[{"id": "t1", "rev": rev(1), "k": "text", "d": "{}"}])
        r = self.ok("delete", board=BOARD, key=key)
        self.assertEqual(1, r["images"])
        self.assertEqual({}, main._blob.data)
        status, _ = self.call("pull", board=BOARD, key=key, since=0)
        self.assertEqual(404, status)

    def test_server_error_is_short(self):
        class Broken(MemoryStore):
            def get_board(self, board_id):
                raise RuntimeError("секретная подробность")
        main._store = Broken()
        status, body = self.call("info", board=BOARD, key="k")
        self.assertEqual(500, status)
        self.assertNotIn("секретная", body["message"])


if __name__ == "__main__":
    unittest.main()
