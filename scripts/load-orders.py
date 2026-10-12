"""Closed-loop HTTP concurrency benchmark for a fresh, disposable local Compose DB."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import http.cookiejar
import json
import math
import os
from pathlib import Path
import platform
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


class Client:
    def __init__(self, base):
        self.base = base
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None

    def call(self, path, data=None, key=None, form=False):
        headers = {"Accept": "application/json"}
        if data is not None:
            headers[self.csrf["headerName"]] = self.csrf["token"]
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        if key:
            headers["Idempotency-Key"] = key
        body = None if data is None else (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
        request = urllib.request.Request(self.base + path, data=body, headers=headers)
        try:
            response = self.opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.load(response), dict(response.headers)

    def login(self):
        self.csrf = self.call("/auth/csrf")[1]
        account = {"email": f"load-{uuid.uuid4().hex}@example.test", "password": secrets.token_hex(16)}
        assert self.call("/auth/register", account)[0] == 201, "Registration failed"
        assert self.call("/auth/login", account, form=True)[0] == 200, "Login failed"
        self.csrf = self.call("/auth/csrf")[1]
        return self

    def worker(self):
        # Each worker owns its opener/cookie jar, even for a shared authenticated session.
        result = Client(self.base)
        for cookie in self.cookies:
            result.cookies.set_cookie(cookie)
        result.csrf = self.csrf
        return result

    def orders(self):
        orders, before = [], None
        while True:
            status, page, _ = self.call("/orders" + (f"?beforeId={before}" if before else ""))
            assert status == 200
            orders.extend(page["orders"])
            before = page["nextBeforeId"]
            if not before:
                return orders


def batch(clients, count, operation):
    workers = len(clients)
    barrier = threading.Barrier(workers)

    def run(worker):
        client = clients[worker].worker()
        rows = []
        barrier.wait(timeout=20)
        for index in range(worker, count, workers):
            started = time.perf_counter()
            status, body, headers = operation(client, index)
            rows.append({"status": status, "body": body, "headers": headers,
                         "ms": (time.perf_counter() - started) * 1000})
        return rows

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        rows = [row for group in pool.map(run, range(workers)) for row in group]
    elapsed = time.perf_counter() - started
    durations = sorted(row["ms"] for row in rows)

    def percentile(fraction):
        return round(durations[math.ceil(len(durations) * fraction) - 1], 2)

    summary = {"requests": count, "concurrency": workers, "elapsedSeconds": round(elapsed, 3),
               "requestsPerSecond": round(count / elapsed, 2), "p50Ms": percentile(.50),
               "p95Ms": percentile(.95), "p99Ms": percentile(.99), "maxMs": round(max(durations), 2),
               "statusCounts": dict(Counter(str(row["status"]) for row in rows))}
    return rows, summary


def database(args, sql):
    root = Path(__file__).resolve().parents[1]
    command = ["docker", "compose", "--project-name", args.project, "--env-file", str(args.env_file.resolve()),
               "-f", str(root / "compose.yaml"), "exec", "-T", "db", "sh", "-c",
               'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 '
               '--user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names']
    result = subprocess.run(command, input=sql + "\n", text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Disposable DB verification failed; inspect its local container logs.")
    return result.stdout.strip()


def db_counters(args):
    values = database(args, "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time');")
    return {name: int(value) for name, value in (line.split("\t") for line in values.splitlines())}


def measure(args, clients, count, operation):
    before = db_counters(args)
    rows, result = batch(clients, count, operation)
    after = db_counters(args)
    result["mysqlRowLockDelta"] = {name: after[name] - value for name, value in before.items()}
    return rows, result


def run_round(args, concurrency):
    public = Client(args.base_url)
    product = next(p for p in public.call("/products")[1] if p["id"] == 1)
    assert product["stock"] == 10, "Expected fresh seed stock of 10; no shared inventory is reset"
    initial = product["stock"]
    clients = [Client(args.base_url).login() for _ in range(concurrency)]
    body = {"productId": product["id"], "quantity": 1}
    rows, oversell = measure(args, clients, args.requests,
                            lambda client, index: client.call("/orders", body, uuid.uuid4().hex))
    created = [row for row in rows if row["status"] == 201]
    rejected = [row for row in rows if row["status"] == 409 and row["body"].get("code") == "INSUFFICIENT_STOCK"]
    assert len(created) == initial and len(rejected) == args.requests - initial, "Unexpected oversell responses"
    assert public.call("/products")[1][0]["stock"] == 0, "Stock must reach exactly zero"
    orders = [(client, order) for client in clients for order in client.orders()]
    assert len(orders) == initial and len({order["orderId"] for _, order in orders}) == initial
    assert all(order["status"] == "CREATED" and order["items"][0]["quantity"] == 1 for _, order in orders)
    assert database(args, "SELECT COUNT(*) FROM purchase_orders;") == str(initial)
    oversell["checks"] = {"created": initial, "expectedStockRejections": len(rejected), "remainingStock": 0,
                          "databaseOrderCount": initial, "oversold": False}

    # Cancel every successful order through its actual owner and recover the original inventory.
    for client, order in orders:
        assert client.call(f"/orders/{order['orderId']}/cancel", {})[0] == 200
    assert public.call("/products")[1][0]["stock"] == initial

    shared = Client(args.base_url).login()
    key = uuid.uuid4().hex
    rows, replay = measure(args, [shared] * concurrency, args.requests,
                          lambda client, index: client.call("/orders", body, key))
    assert sum(row["status"] == 201 for row in rows) == 1
    assert sum(row["status"] == 200 for row in rows) == args.requests - 1
    order_ids = {row["body"]["orderId"] for row in rows}
    assert len(order_ids) == 1 and len(shared.orders()) == 1
    assert all(row["body"]["status"] == "CREATED" for row in rows)
    assert public.call("/products")[1][0]["stock"] == initial - 1
    assert database(args, "SELECT COUNT(*) FROM purchase_orders;") == str(initial + 1)
    replay["checks"] = {"newOrders": 1, "replays": args.requests - 1, "stockDecreasedBy": 1}

    order_id = next(iter(order_ids))
    rows, cancellation = measure(args, [shared] * concurrency, args.requests,
                                lambda client, index: client.call(f"/orders/{order_id}/cancel", {}))
    assert all(row["status"] == 200 and row["body"]["status"] == "CANCELLED" for row in rows)
    assert len({row["body"]["cancelledAt"] for row in rows}) == 1
    assert public.call("/products")[1][0]["stock"] == initial
    assert database(args, "SELECT COUNT(*) FROM purchase_orders WHERE status <> 'CANCELLED';") == '0'
    cancellation["checks"] = {"sameCancellationTimestamp": True, "stockRestoredBy": 1, "finalStock": initial}
    print(f"PASS: concurrency={concurrency}; stock 10, orders 10, replay one order, repeated cancellation restores once")
    return {"concurrency": concurrency, "oversell": oversell, "idempotency": replay, "cancellation": cancellation}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18084")
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--concurrency", default=32, type=int)
    parser.add_argument("--requests", default=100, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    target = urllib.parse.urlsplit(args.base_url)
    if (os.environ.get("LOAD_DISPOSABLE") != "true" or target.scheme != "http"
            or target.hostname not in ["127.0.0.1", "localhost"] or target.port != 18084
            or target.username or target.password or target.path not in ["", "/"] or target.query or target.fragment
            or not args.project.startswith("order-stock-load-") or not args.env_file.is_file()):
        parser.error("Use a fresh order-stock-load-* Compose project on loopback HTTP 18084 with LOAD_DISPOSABLE=true")
    if not 1 <= args.concurrency <= min(64, args.requests) or not 32 <= args.requests <= 1000:
        parser.error("Use 32..1000 requests and 1..64 workers (no more workers than requests)")
    assert database(args, "SELECT DATABASE();") == "order_stock"
    assert database(args, "SELECT COUNT(*) FROM app_users;") == '0', "Fresh disposable DB required"
    assert database(args, "SELECT COUNT(*) FROM purchase_orders;") == '0', "Fresh disposable DB required"
    args.base_url = args.base_url.rstrip("/")
    rounds = [run_round(args, args.concurrency)]
    report = {"format": "order-stock-load-v1", "measuredAtUtc": datetime.now(timezone.utc).isoformat(),
              "python": platform.python_version(), "clientPlatform": platform.system(), "seedStock": 10,
              "method": "closed-loop; authentication, DB inspection and cleanup excluded from request latencies",
              "rounds": rounds, "allInvariantsPassed": True}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: sanitized latency/status/lock summaries saved to {args.output}")


if __name__ == "__main__":
    main()
