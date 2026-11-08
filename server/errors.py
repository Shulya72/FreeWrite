"""Ошибки сервера: код HTTP, короткий код для приложения и понятное сообщение по-русски."""


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def not_found() -> ApiError:
    return ApiError(404, "no_board", "Доски нет на сервере")


def forbidden() -> ApiError:
    return ApiError(403, "bad_key", "Нет доступа к доске")


def busy() -> ApiError:
    return ApiError(503, "busy", "Сервер занят, повторите чуть позже")
