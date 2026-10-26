"""Downloads a submitted statement from the presigned URL in the event.

Only URLs under the configured object-store origins are fetched (the event is internal, but a URL is still never
followed blindly), redirects are refused, the size is capped and the bytes must hash to the submitted SHA-256.
"""
from __future__ import annotations

import hashlib
import urllib.error
import urllib.request
from urllib.parse import urlsplit


class FetchError(Exception):
    def __init__(self, code: str, detail: str):
        super().__init__(detail)
        self.code = code


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401 - urllib hook
        raise FetchError("FILE_UNAVAILABLE", f"redirect refused ({code})")


_OPENER = urllib.request.build_opener(_NoRedirect, urllib.request.ProxyHandler({}))


def origin(url: str) -> str:
    p = urlsplit(url)
    return f"{p.scheme}://{p.netloc}".lower()


def fetch(url: str, sha256: str, size: int, allowed: set[str], max_bytes: int, timeout: float = 20.0) -> bytes:
    if origin(url) not in allowed:
        raise FetchError("FILE_UNAVAILABLE", "the file URL is not on the object store")
    if size > max_bytes:
        raise FetchError("FILE_TOO_LARGE", f"at most {max_bytes} bytes")
    try:
        with _OPENER.open(urllib.request.Request(url, method="GET"), timeout=timeout) as r:
            data = r.read(max_bytes + 1)
    except FetchError:
        raise
    except urllib.error.HTTPError as e:
        raise FetchError("FILE_UNAVAILABLE", f"object store answered {e.code} (expired link or deleted file)") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise FetchError("FILE_UNREACHABLE", f"object store unreachable: {type(e).__name__}") from e
    if len(data) > max_bytes:
        raise FetchError("FILE_TOO_LARGE", f"at most {max_bytes} bytes")
    if hashlib.sha256(data).hexdigest() != sha256.lower():
        raise FetchError("FILE_CHANGED", "the file does not match the submitted checksum")
    return data
