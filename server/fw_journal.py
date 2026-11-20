"""Журнал (0.28.3): какие строки писать в history. Без YDB — проверяется обычными тестами."""


def history_rows(board_id, now, accepted, old_rows):
    """Строки журнала: прежние версии предметов, которые заменили принятые правки (удалённые версии — без данных, не пишем)."""
    out = []
    for c in accepted:
        old = old_rows.get(c["id"])
        if old is None or getattr(old, "deleted", False) or not (getattr(old, "body", "") or ""):
            continue
        out.append({"board_id": board_id, "at": now, "id": c["id"], "rev": old.rev, "kind": old.kind or "",
                    "body": old.body, "device": old.device or "", "author": old.author or "",
                    "by_rev": c["rev"], "by_device": c["device"], "by_author": c["author"],
                    "by_deleted": bool(c["deleted"])})
    return out
