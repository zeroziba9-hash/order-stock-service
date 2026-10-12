"""Verify the production stack through real HTTPS, trusting only the disposable Caddy CA."""

import argparse
from http.cookiejar import CookieJar
import json
import secrets
import ssl
import urllib.error
import urllib.parse
import urllib.request
import uuid


HTTPS_URL = "https://localhost:18443"
HTTP_URL = "http://localhost:18081"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


class Client:
    def __init__(self, ca_file):
        self.cookies = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=ca_file)),
            urllib.request.HTTPCookieProcessor(self.cookies), NoRedirect(),
            urllib.request.ProxyHandler({}),
        )

    def request(self, path, *, data=None, form=None, key=None, csrf=True, expected=200):
        headers = {}
        body = None
        if data is not None or form is not None:
            if csrf:
                token = self.request("/auth/csrf")
                headers[token["headerName"]] = token["token"]
            if form is not None:
                headers["Content-Type"] = "application/x-www-form-urlencoded"
                body = urllib.parse.urlencode(form).encode()
            else:
                headers["Content-Type"] = "application/json"
                body = json.dumps(data).encode()
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(HTTPS_URL + path, data=body, headers=headers)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.status == expected, (path, response.status, expected)
            return json.load(response) if response.status != 204 else None

    def session_cookie(self):
        return next(cookie for cookie in self.cookies if cookie.name == "JSESSIONID")


def verify(ca_file):
    # A valid CA is required; no global trust-store changes or insecure TLS bypasses are used.
    untrusted = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        untrusted.open(HTTPS_URL + "/products", timeout=15)
    except urllib.error.URLError as error:
        assert isinstance(error.reason, ssl.SSLCertVerificationError), type(error.reason)
    else:
        raise AssertionError("The disposable CA should not already be trusted by the host")

    http = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
    try:
        http.open(HTTP_URL + "/products", timeout=15)
    except urllib.error.HTTPError as response:
        assert response.code == 308
        location = urllib.parse.urlsplit(response.headers["Location"])
        assert location.scheme == "https" and location.hostname == "localhost"
        assert location.path == "/products"
    else:
        raise AssertionError("HTTP must redirect to HTTPS")

    client = Client(ca_file)
    assert client.request("/actuator/health/readiness") == {"status": "UP"}
    assert client.request("/auth/me", expected=401)["code"] == "AUTH_REQUIRED"
    account = {"email": f"https-{uuid.uuid4().hex}@example.com", "password": secrets.token_hex(16)}
    assert client.request("/auth/register", data=account, csrf=False, expected=403)["code"] == "ACCESS_DENIED"
    client.request("/auth/register", data=account, expected=201)
    before_login = client.session_cookie().value
    client.request("/auth/login", form=account)
    cookie = client.session_cookie()
    assert cookie.value != before_login, "Login must rotate the session ID"
    assert cookie.secure and cookie.has_nonstandard_attr("HttpOnly")
    assert cookie.get_nonstandard_attr("SameSite").lower() == "lax"
    http_request = urllib.request.Request(HTTP_URL + "/auth/me")
    client.cookies.add_cookie_header(http_request)
    assert not http_request.has_header("Cookie"), "Secure session cookies must not be sent over HTTP"
    assert client.request("/auth/me")["email"] == account["email"]

    product = next(p for p in client.request("/products") if p["stock"] >= 1)
    payload = {"productId": product["id"], "quantity": 1}
    key = uuid.uuid4().hex
    order = client.request("/orders", data=payload, key=key, expected=201)
    assert client.request("/orders", data=payload, key=key) == order
    path = f"/orders/{order['orderId']}"
    assert order["status"] == "CREATED"
    for operation in ["payments", "refunds"]:
        result = client.request(path + "/" + operation, data={"result": "SUCCESS"},
                                key=uuid.uuid4().hex, expected=404)
        assert result["code"] == "MOCK_API_DISABLED"
        assert client.request(path + "/" + operation) == []
    assert client.request(path) == order
    stock = next(p["stock"] for p in client.request("/products") if p["id"] == product["id"])
    assert stock == product["stock"] - 1
    cancelled = client.request(path + "/cancel", data={})
    assert cancelled["status"] == "CANCELLED"
    assert client.request(path + "/cancel", data={}) == cancelled
    stock = next(p["stock"] for p in client.request("/products") if p["id"] == product["id"])
    assert stock == product["stock"]
    client.request("/auth/logout", data={}, expected=204)
    assert client.request("/auth/me", expected=401)["code"] == "AUTH_REQUIRED"
    print("PASS: verified TLS chain, HTTP redirect, Secure/HttpOnly/Lax cookie, session rotation, CSRF, login/logout")
    print("PASS: prod order replay/cancellation, stock restoration and disabled mock payment/refund writes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ca-file", required=True, help="Public root certificate copied from the disposable proxy")
    verify(parser.parse_args().ca_file)
