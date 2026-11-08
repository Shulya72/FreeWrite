#!/usr/bin/env bash
# Создаёт (если ещё нет) всё для сервера FreeWrite в каталоге YC_FOLDER_ID и пишет настройки
# в $GITHUB_ENV. Повторный запуск ничего не ломает. Номера и адреса в журнал не попадают
# (::add-mask::) — репозиторий публичный.
set -euo pipefail
: "${YC_FOLDER_ID:?нет YC_FOLDER_ID}"
mask() { [ -n "$1" ] && echo "::add-mask::$1"; }
out() { echo "$1=$2" >> "$GITHUB_ENV"; }

# 1. Робот сервера (от его имени функция работает с базой и хранилищем).
SA_ID=$(yc iam service-account get --name freewrite-server --format json 2>/dev/null | jq -r '.id // empty' || true)
if [ -z "$SA_ID" ]; then
  SA_ID=$(yc iam service-account create --name freewrite-server \
    --description "Сервер FreeWrite: база и картинки" --format json | jq -r .id)
  echo "Создан робот сервера freewrite-server"
fi
mask "$SA_ID"
BINDINGS=$(yc resource-manager folder list-access-bindings "$YC_FOLDER_ID" --format json)
for role in ydb.editor storage.editor logging.writer; do
  if jq -e --arg r "$role" --arg s "$SA_ID" 'any(.[]; .role_id == $r and .subject.id == $s)' <<<"$BINDINGS" > /dev/null; then
    echo "Роль $role у робота сервера: есть"
  else
    yc resource-manager folder add-access-binding "$YC_FOLDER_ID" --role "$role" --service-account-id "$SA_ID" > /dev/null
    echo "Роль $role роботу сервера: выдана"
  fi
done
out SA_ID "$SA_ID"

# 2. База YDB (бессерверная, предел хранения 1 ГБ — как бесплатный лимит).
if ! yc ydb database get --name freewrite-db --format json > /dev/null 2>&1; then
  yc ydb database create --name freewrite-db --serverless --sls-storage-size 1GB --format json > /dev/null
  echo "Создана база freewrite-db"
fi
for i in $(seq 1 60); do
  DB=$(yc ydb database get --name freewrite-db --format json)
  [ "$(jq -r .status <<<"$DB")" = "RUNNING" ] && break
  sleep 5
done
[ "$(jq -r .status <<<"$DB")" = "RUNNING" ] || { echo "::error::База не запустилась"; exit 1; }
EP=$(jq -r .endpoint <<<"$DB")
YDB_ENDPOINT="${EP%%/?database=*}"
YDB_DATABASE="${EP##*database=}"
mask "$YDB_DATABASE"
echo "База freewrite-db: работает"
out YDB_ENDPOINT "$YDB_ENDPOINT"
out YDB_DATABASE "$YDB_DATABASE"

# 3. Хранилище картинок (закрытое, не больше 1 ГБ). Имя — постоянное для каталога.
BUCKET="freewrite-$(printf '%s' "$YC_FOLDER_ID" | sha256sum | cut -c1-12)"
mask "$BUCKET"
if ! yc storage bucket get --name "$BUCKET" > /dev/null 2>&1; then
  yc storage bucket create --name "$BUCKET" --max-size 1073741824 > /dev/null
  echo "Создано хранилище картинок"
else
  echo "Хранилище картинок: есть"
fi
out BUCKET "$BUCKET"

# 4. Группа журналов по умолчанию (туда пишет функция; бесплатно до 5 ГБ в месяц, хранение 3 дня).
if ! yc logging group get --name default > /dev/null 2>&1; then
  yc logging group create --name default --retention-period 72h > /dev/null
  echo "Создана группа журналов default"
else
  echo "Группа журналов default: есть"
fi

# 5. Функция-сервер, открытая для вызова из приложения (доступ к доскам — по ключам досок).
if ! yc serverless function get --name freewrite-sync --format json > /dev/null 2>&1; then
  yc serverless function create --name freewrite-sync --description "Сервер общей доски FreeWrite" > /dev/null
  echo "Создана функция freewrite-sync"
fi
FN=$(yc serverless function get --name freewrite-sync --format json)
FUNC_ID=$(jq -r .id <<<"$FN")
FUNC_URL=$(jq -r .http_invoke_url <<<"$FN")
mask "$FUNC_ID"
mask "$FUNC_URL"
yc serverless function allow-unauthenticated-invoke --name freewrite-sync > /dev/null
echo "Функция freewrite-sync: открыта для приложения"
out FUNC_URL "$FUNC_URL"
