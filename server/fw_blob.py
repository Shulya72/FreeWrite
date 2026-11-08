"""
Хранилище картинок — Object Storage (S3). Сервер ходит туда по IAM-токену своего сервисного
аккаунта (заголовок Authorization: Bearer), без отдельных ключей доступа.
Картинка хранится частями по 1 МБ: img/<имя>/<номер части> — так каждая часть влезает
в ограничение функции на размер запроса и ответа (3,5 МБ).
"""
import urllib.error
import urllib.parse
import urllib.request

from fw_errors import ApiError

HOST = "https://storage.yandexcloud.net"


class Blob:
    def __init__(self, bucket: str):
        self.bucket = bucket

    def _request(self, method: str, key: str, token: str, data: bytes = None) -> bytes:
        url = "%s/%s/%s" % (HOST, self.bucket, urllib.parse.quote(key))
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Bearer " + token)
        if data is not None:
            req.add_header("Content-Type", "application/octet-stream")
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.read()

    def put(self, key: str, data: bytes, token: str) -> None:
        self._request("PUT", key, token, data)

    def get(self, key: str, token: str) -> bytes:
        try:
            return self._request("GET", key, token)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise ApiError(404, "no_part", "Части картинки нет на сервере")
            raise

    def delete(self, key: str, token: str) -> None:
        try:
            self._request("DELETE", key, token)
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
