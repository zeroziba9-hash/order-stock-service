"""Exercise a disposable CI Compose stack, then verify its data after container recreation."""

import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import urllib.parse
import urllib.request
import urllib.error
import uuid


BASE_URL = os.environ.get("SMOKE_BASE_URL", f"http://127.0.0.1:{int(os.environ.get('APP_PORT', '18080'))}")


class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies),
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=os.environ.get("SMOKE_CA_FILE"))),
            urllib.request.ProxyHandler({}),
        )

    def request(self, path, *, data=None, form=None, key=None, expected=200, csrf_token=None, session_cookie=None):
        headers = {}
        body = None
        if data is not None or form is not None:
            csrf = csrf_token or self.request("/auth/csrf")
            headers[csrf["headerName"]] = csrf["token"]
            if form is not None:
                headers["Content-Type"] = "application/x-www-form-urlencoded"
                body = urllib.parse.urlencode(form).encode()
            else:
                headers["Content-Type"] = "application/json"
                body = json.dumps(data).encode()
        if key is not None:
            headers["Idempotency-Key"] = key
        if session_cookie is not None:
            assert re.fullmatch(r"[0-9a-f-]{36}", session_cookie)
            headers["Cookie"] = "JSESSIONID=" + session_cookie
        request = urllib.request.Request(BASE_URL + path, data=body, headers=headers)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.status == expected, (path, response.status, expected)
            return json.load(response) if response.status != 204 else None

    def session_id(self):
        return next(cookie.value for cookie in self.cookies if cookie.name == "JSESSIONID")

    def reuse_session(self, value):
        assert re.fullmatch(r"[0-9a-f-]{36}", value)
        host = urllib.parse.urlsplit(BASE_URL).hostname
        self.cookies.set_cookie(http.cookiejar.Cookie(version=0, name="JSESSIONID", value=value,
                port=None, port_specified=False, domain=host, domain_specified=False, domain_initial_dot=False,
                path="/", path_specified=True, secure=False, expires=None, discard=True,
                comment=None, comment_url=None, rest={"HttpOnly": None}))

    def login(self, account):
        self.request("/auth/login", form=account)
        assert self.request("/auth/me")["email"] == account["email"]

    def stock(self, product_id):
        return next(p["stock"] for p in self.request("/products") if p["id"] == product_id)


def prepare(state_path):
    client = Client()
    assert client.request("/actuator/health/readiness")["status"] == "UP"
    product = next(p for p in client.request("/products") if p["stock"] >= 1)
    account = {"email": f"compose-{uuid.uuid4().hex}@example.com", "password": secrets.token_hex(16)}
    client.request("/auth/register", data=account, expected=201)
    client.login(account)

    key = uuid.uuid4().hex
    payload = {"productId": product["id"], "quantity": 1}
    order = client.request("/orders", data=payload, key=key, expected=201)
    assert order["status"] == "CREATED"
    assert order["totalAmount"] == product["price"]
    replay = client.request("/orders", data=payload, key=key)
    assert replay == order, "Order replay must return the original order"
    assert client.stock(product["id"]) == product["stock"] - 1

    path = f"/orders/{order['orderId']}"
    cancelled = client.request(path + "/cancel", data={})
    assert cancelled["status"] == "CANCELLED"
    assert client.request(path + "/cancel", data={}) == cancelled
    assert client.stock(product["id"]) == product["stock"]

    second = client.request("/orders", data=payload, key=uuid.uuid4().hex, expected=201)
    second_path = f"/orders/{second['orderId']}"
    payment_key = uuid.uuid4().hex
    payment = client.request(second_path + "/payments", data={"result": "SUCCESS"},
                             key=payment_key, expected=201)
    assert client.request(second_path + "/payments", data={"result": "SUCCESS"}, key=payment_key) == payment
    assert client.request(second_path)["status"] == "PAID"
    refund_key = uuid.uuid4().hex
    refund = client.request(second_path + "/refunds", data={"result": "SUCCESS"},
                            key=refund_key, expected=201)
    assert client.request(second_path + "/refunds", data={"result": "SUCCESS"}, key=refund_key) == refund
    refunded = client.request(second_path)
    assert refunded["status"] == "REFUNDED"
    assert client.stock(product["id"]) == product["stock"]
    assert client.request(second_path + "/cancel", data={}) == refunded
    assert client.stock(product["id"]) == product["stock"]

    state_path.write_text(json.dumps({
        "account": account,
        "orders": [cancelled, refunded],
        "request": {"payload": payload, "key": key},
        "products": client.request("/products"),
        "payment": payment,
        "refund": refund,
        "sessionId": client.session_id(),
        "csrf": client.request("/auth/csrf"),
    }), encoding="utf-8")
    state_path.chmod(0o600)
    print("PASS: health, seed data, session/CSRF, order replay, cancellation, mock payment/refund and stock restoration")


def verify(state_path, expect_session_invalidated=False):
    state = json.loads(state_path.read_text(encoding="utf-8"))
    client = Client()
    assert client.request("/actuator/health/readiness")["status"] == "UP"
    if expect_session_invalidated:
        assert client.request("/auth/me", session_cookie=state["sessionId"], expected=401)["code"] == "AUTH_REQUIRED"
        client.login(state["account"])
        print("PASS: restored backup session rejected; fresh login required")
    else:
        client.reuse_session(state["sessionId"])
        assert client.request("/auth/me")["email"] == state["account"]["email"]
        print("PASS: original cookie authenticates after app/DB container recreation without login")
    assert client.request("/products") == state["products"]
    for order in state["orders"]:
        assert client.request(f"/orders/{order['orderId']}") == order
    page = client.request("/orders")
    assert page == {"orders": list(reversed(state["orders"])), "nextBeforeId": None}
    request = state["request"]
    token = None if expect_session_invalidated else state["csrf"]
    assert client.request("/orders", data=request["payload"], key=request["key"], csrf_token=token) == state["orders"][0]
    assert client.request("/products") == state["products"]
    refunded = state["orders"][1]
    path = f"/orders/{refunded['orderId']}"
    assert client.request(path + "/payments") == [state["payment"]]
    assert client.request(path + "/refunds") == [state["refund"]]
    if not expect_session_invalidated:
        client.request("/auth/logout", data={}, expected=204)
        assert client.request("/auth/me", session_cookie=state["sessionId"], expected=401)["code"] == "AUTH_REQUIRED"
    print("PASS: account, stock, orders and payment/refund history match the saved snapshot")


def verify_logged_out(state_path):
    state = json.loads(state_path.read_text(encoding="utf-8"))
    client = Client()
    assert client.request("/actuator/health/readiness")["status"] == "UP"
    assert client.request("/auth/me", session_cookie=state["sessionId"], expected=401)["code"] == "AUTH_REQUIRED"
    client.login(state["account"])
    assert client.request("/products") == state["products"]
    print("PASS: logout remains revoked after another app recreation; fresh login and inventory verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["prepare", "verify", "verify-logged-out"])
    parser.add_argument("--state-file", type=Path, required=True,
                        help="Temporary CI file with disposable test credentials; never commit it")
    parser.add_argument("--expect-session-invalidated", action="store_true",
                        help="Backup recovery must clear stored sessions before starting the app")
    args = parser.parse_args()
    if args.phase == "prepare":
        prepare(args.state_file)
    elif args.phase == "verify":
        verify(args.state_file, args.expect_session_invalidated)
    else:
        verify_logged_out(args.state_file)
