"""
База доски — YDB (бессерверный режим). Таблицы создаются сами при первом запуске функции.

boards   — доска: ключ, название, код-приглашение, последний номер правки seq, число предметов;
invites  — код-приглашение → доска;
objects  — предметы досок (доска, id) → rev, вид, данные, удалён ли, seq; индекс по (доска, seq)
           для «что нового после seq»;
images   — готовые картинки: имя → доска, число частей, размер.

Ответ YDB на один запрос — не больше 1000 строк, поэтому id в запросах идут пачками по 500,
а pull отдаёт не больше 500 предметов за раз.
"""
import hmac
import json

import ydb
import ydb.iam
import ydb.retries

from errors import ApiError, busy, forbidden, not_found

TABLES = [
    ("boards", """
        CREATE TABLE boards (
            board_id Utf8, secret Utf8, title Utf8, invite Utf8,
            seq Uint64, n_objects Uint64, created Uint64, updated Uint64, owner Utf8,
            PRIMARY KEY (board_id)
        )"""),
    ("invites", """
        CREATE TABLE invites (
            code Utf8, board_id Utf8,
            PRIMARY KEY (code)
        )"""),
    ("objects", """
        CREATE TABLE objects (
            board_id Utf8, id Utf8, rev Utf8, kind Utf8, deleted Bool, body Utf8, seq Uint64, device Utf8,
            PRIMARY KEY (board_id, id),
            INDEX idx_seq GLOBAL SYNC ON (board_id, seq)
        )"""),
    ("images", """
        CREATE TABLE images (
            name Utf8, board_id Utf8, parts Uint32, bytes Uint64, created Uint64,
            PRIMARY KEY (name)
        )"""),
]

Q_BOARD = """
DECLARE $b AS Utf8;
SELECT board_id, secret, title, invite, seq, n_objects FROM boards WHERE board_id = $b;
"""
Q_COUNT_BOARDS = "SELECT COUNT(*) AS n FROM boards;"
Q_INVITE = """
DECLARE $c AS Utf8;
SELECT board_id FROM invites WHERE code = $c;
"""
Q_CREATE = """
DECLARE $b AS Utf8; DECLARE $secret AS Utf8; DECLARE $title AS Utf8; DECLARE $invite AS Utf8;
DECLARE $owner AS Utf8; DECLARE $now AS Uint64;
INSERT INTO boards (board_id, secret, title, invite, seq, n_objects, created, updated, owner)
VALUES ($b, $secret, $title, $invite, 0ul, 0ul, $now, $now, $owner);
INSERT INTO invites (code, board_id) VALUES ($invite, $b);
"""
Q_REVS = """
DECLARE $b AS Utf8; DECLARE $ids AS List<Utf8>;
SELECT id, rev FROM objects WHERE board_id = $b AND id IN $ids;
"""
Q_UPSERT = """
DECLARE $rows AS List<Struct<board_id: Utf8, id: Utf8, rev: Utf8, kind: Utf8, deleted: Bool,
                             body: Utf8, seq: Uint64, device: Utf8>>;
UPSERT INTO objects SELECT * FROM AS_TABLE($rows);
"""
Q_BOARD_UPDATE = """
DECLARE $b AS Utf8; DECLARE $seq AS Uint64; DECLARE $n AS Uint64; DECLARE $now AS Uint64;
DECLARE $title AS Optional<Utf8>;
UPDATE boards SET seq = $seq, n_objects = $n, updated = $now, title = COALESCE($title, title)
WHERE board_id = $b;
"""
Q_PULL = """
DECLARE $b AS Utf8; DECLARE $s AS Uint64; DECLARE $n AS Uint64;
SELECT id, rev, kind, deleted, body, seq FROM objects VIEW idx_seq
WHERE board_id = $b AND seq > $s
ORDER BY seq
LIMIT $n;
"""
Q_IMAGES = """
DECLARE $names AS List<Utf8>;
SELECT name, parts, bytes FROM images WHERE name IN $names;
"""
Q_IMAGES_TOTAL = "SELECT SUM(bytes) AS total FROM images;"
Q_IMAGE_PUT = """
DECLARE $name AS Utf8; DECLARE $b AS Utf8; DECLARE $parts AS Uint32; DECLARE $bytes AS Uint64;
DECLARE $now AS Uint64;
UPSERT INTO images (name, board_id, parts, bytes, created) VALUES ($name, $b, $parts, $bytes, $now);
"""
Q_BOARD_IMAGES = """
DECLARE $b AS Utf8;
SELECT name, parts FROM images WHERE board_id = $b;
"""
Q_DELETE = """
DECLARE $b AS Utf8; DECLARE $invite AS Utf8;
DELETE FROM images WHERE board_id = $b;
DELETE FROM objects WHERE board_id = $b;
DELETE FROM invites WHERE code = $invite;
DELETE FROM boards WHERE board_id = $b;
"""

# Не больше 4 повторов и коротких пауз: функция должна ответить за 20 с; «занят» — пусть приложение
# повторит позже само.
RETRY = ydb.RetrySettings(
    max_retries=4,
    slow_backoff_settings=ydb.retries.BackoffSettings(ceiling=3, slot_duration=0.5),
)


def _title_of(changes):
    """Новое название доски, если среди правок есть «~title»."""
    for c in changes:
        if c["id"] == "~title" and not c["deleted"]:
            try:
                t = json.loads(c["body"]).get("title")
                if isinstance(t, str):
                    return t[:200]
            except (ValueError, AttributeError):
                pass
    return None


class YdbStore:
    def __init__(self, endpoint: str, database: str):
        self.database = database
        self.driver = ydb.Driver(endpoint=endpoint, database=database,
                                 credentials=ydb.iam.MetadataUrlCredentials())
        self.driver.wait(fail_fast=True, timeout=10)
        self.pool = ydb.SessionPool(self.driver, size=4)
        self._ensure_schema()

    # ------------------------------------------------------------ служебное

    def _run(self, fn):
        try:
            return self.pool.retry_operation_sync(fn, RETRY)
        except (ydb.Overloaded, ydb.Unavailable) as e:
            print("YDB busy: %s" % e)
            raise busy()

    def _ensure_schema(self):
        def fn(session):
            for name, ddl in TABLES:
                try:
                    session.describe_table("%s/%s" % (self.database, name))
                except (ydb.SchemeError, ydb.NotFound):
                    print("create table %s" % name)
                    session.execute_scheme(ddl)
        self._run(fn)

    def _read(self, query: str, params: dict):
        def fn(session):
            q = session.prepare(query)
            return session.transaction(ydb.OnlineReadOnly()).execute(q, params, commit_tx=True)
        return self._run(fn)

    @staticmethod
    def _board_row(r):
        return {"board_id": r.board_id, "secret": r.secret, "title": r.title or "", "invite": r.invite,
                "seq": r.seq or 0, "n_objects": r.n_objects or 0}

    # ------------------------------------------------------------ доски

    def get_board(self, board_id: str):
        rows = self._read(Q_BOARD, {"$b": board_id})[0].rows
        return self._board_row(rows[0]) if rows else None

    def count_boards(self) -> int:
        rows = self._read(Q_COUNT_BOARDS, {})[0].rows
        return rows[0].n if rows else 0

    def find_invite(self, code: str):
        rows = self._read(Q_INVITE, {"$c": code})[0].rows
        return rows[0].board_id if rows else None

    def create_board(self, board_id, secret, title, invite, owner, now) -> bool:
        def fn(session):
            q = session.prepare(Q_CREATE)
            session.transaction(ydb.SerializableReadWrite()).execute(q, {
                "$b": board_id, "$secret": secret, "$title": title, "$invite": invite,
                "$owner": owner, "$now": now}, commit_tx=True)
        try:
            self._run(fn)
            return True
        except ydb.PreconditionFailed:
            return False

    def delete_board(self, board_id: str, key: str):
        """Удалить доску; вернуть её картинки [(имя, частей)] — их части удалит вызывающий."""
        def fn(session):
            tx = session.transaction(ydb.SerializableReadWrite())
            rows = tx.execute(session.prepare(Q_BOARD), {"$b": board_id})[0].rows
            if not rows:
                tx.rollback()
                raise not_found()
            b = self._board_row(rows[0])
            if not hmac.compare_digest(b["secret"].encode(), key.encode()):
                tx.rollback()
                raise forbidden()
            imgs = tx.execute(session.prepare(Q_BOARD_IMAGES), {"$b": board_id})[0].rows
            tx.execute(session.prepare(Q_DELETE), {"$b": board_id, "$invite": b["invite"]}, commit_tx=True)
            return [(r.name, r.parts) for r in imgs]
        return self._run(fn)

    # ------------------------------------------------------------ правки

    def push(self, board_id, key, changes, device, now, max_objects):
        """Принять правки, которые позже серверных. Вернуть (seq доски, сколько принято)."""
        def fn(session):
            tx = session.transaction(ydb.SerializableReadWrite())
            rows = tx.execute(session.prepare(Q_BOARD), {"$b": board_id})[0].rows
            if not rows:
                tx.rollback()
                raise not_found()
            b = self._board_row(rows[0])
            if not hmac.compare_digest(b["secret"].encode(), key.encode()):
                tx.rollback()
                raise forbidden()
            cur = {}
            q_revs = session.prepare(Q_REVS)
            ids = [c["id"] for c in changes]
            for i in range(0, len(ids), 500):
                for r in tx.execute(q_revs, {"$b": board_id, "$ids": ids[i:i + 500]})[0].rows:
                    cur[r.id] = r.rev
            seq = b["seq"]
            count = b["n_objects"]
            out = []
            for c in changes:
                old = cur.get(c["id"])
                if old is not None and c["rev"] <= old:
                    continue
                if old is None:
                    count += 1
                seq += 1
                out.append({"board_id": board_id, "id": c["id"], "rev": c["rev"], "kind": c["kind"],
                            "deleted": c["deleted"], "body": c["body"], "seq": seq, "device": device})
            if count > max_objects:
                tx.rollback()
                raise ApiError(507, "full", "На доске слишком много предметов для сервера")
            if not out:
                tx.commit()
                return b["seq"], 0
            q_up = session.prepare(Q_UPSERT)
            for i in range(0, len(out), 500):
                tx.execute(q_up, {"$rows": out[i:i + 500]})
            title = _title_of([c for c in changes if c["id"] in {o["id"] for o in out}])
            tx.execute(session.prepare(Q_BOARD_UPDATE), {"$b": board_id, "$seq": seq, "$n": count,
                                                         "$now": now, "$title": title}, commit_tx=True)
            return seq, len(out)
        return self._run(fn)

    def pull(self, board_id, key, since, limit):
        """(seq доски, название, правки после since по порядку seq — не больше limit)."""
        def fn(session):
            tx = session.transaction(ydb.SnapshotReadOnly())
            rows = tx.execute(session.prepare(Q_BOARD), {"$b": board_id})[0].rows
            if not rows:
                tx.commit()
                raise not_found()
            b = self._board_row(rows[0])
            if not hmac.compare_digest(b["secret"].encode(), key.encode()):
                tx.commit()
                raise forbidden()
            if since >= b["seq"]:
                tx.commit()
                return b["seq"], b["title"], []
            rs = tx.execute(session.prepare(Q_PULL), {"$b": board_id, "$s": since, "$n": limit},
                            commit_tx=True)
            out = [{"id": r.id, "rev": r.rev, "kind": r.kind or "", "deleted": bool(r.deleted),
                    "body": r.body or "", "seq": r.seq} for r in rs[0].rows]
            return b["seq"], b["title"], out
        return self._run(fn)

    # ------------------------------------------------------------ картинки

    def images(self, names):
        """Готовые картинки из списка: имя → {parts, size}."""
        out = {}
        for i in range(0, len(names), 500):
            for r in self._read(Q_IMAGES, {"$names": list(names[i:i + 500])})[0].rows:
                out[r.name] = {"parts": r.parts, "size": r.bytes}
        return out

    def images_total(self) -> int:
        rows = self._read(Q_IMAGES_TOTAL, {})[0].rows
        return (rows[0].total or 0) if rows else 0

    def put_image(self, name, board_id, parts, size, now):
        def fn(session):
            q = session.prepare(Q_IMAGE_PUT)
            session.transaction(ydb.SerializableReadWrite()).execute(q, {
                "$name": name, "$b": board_id, "$parts": parts, "$bytes": size, "$now": now}, commit_tx=True)
        self._run(fn)
