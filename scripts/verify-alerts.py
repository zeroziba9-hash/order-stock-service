"""Exercise real alert states only in a fresh disposable loopback monitoring stack."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request
import uuid

RULES = {"StockroomAppDown", "StockroomServerErrors", "StockroomAuthRateLimited",
         "StockroomAuthCapacity", "StockroomExpirationFailures"}
BASE = "http://127.0.0.1:18089"
PROMETHEUS = "http://127.0.0.1:19091"


def read(path):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(PROMETHEUS + path, timeout=10) as response:
        return json.load(response)


def eventually(probe, seconds=100):
    deadline = time.monotonic() + seconds
    while True:
        try:
            result = probe()
            if result:
                return result
        except (OSError, ValueError, KeyError, IndexError):
            pass
        if time.monotonic() >= deadline:
            raise AssertionError("Alert state did not converge; inspect the disposable stack")
        time.sleep(1)


def state(name):
    alerts = read("/api/v1/alerts")["data"]["alerts"]
    matches = [row["state"] for row in alerts if row["labels"].get("alertname") == name]
    return "firing" if "firing" in matches else "pending" if "pending" in matches else "inactive"


def query(expression):
    response = read("/api/v1/query?" + urllib.parse.urlencode({"query": expression}))
    assert response["status"] == "success"
    return response["data"]["result"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if (os.environ.get("ALERT_DISPOSABLE") != "true" or not args.project.startswith("order-stock-alerts-")
            or not args.env_file.is_file()):
        parser.error("Requires ALERT_DISPOSABLE=true and a fresh order-stock-alerts-* stack")
    root = Path(__file__).resolve().parents[1]
    command = ["docker", "compose", "-p", args.project, "--env-file", str(args.env_file.resolve()),
               "-f", str(root / "compose.yaml"), "-f", str(root / "monitoring/compose.observability.yaml")]

    def compose(*arguments):
        result = subprocess.run(command + list(arguments), capture_output=True, text=True,
                                encoding="utf-8", timeout=150)
        if result.returncode:
            raise RuntimeError("Disposable Compose operation failed; inspect its local logs")
        return result.stdout.strip()

    # Verify actual container ownership and port bindings before any stop operation.
    for service, port in [("app", "18089"), ("prometheus", "19091")]:
        container = compose("ps", "-q", service)
        assert container and "\n" not in container
        result = subprocess.run(["docker", "inspect", container], capture_output=True, text=True,
                                encoding="utf-8", timeout=15)
        if result.returncode:
            raise RuntimeError("Container identity could not be checked")
        details = json.loads(result.stdout)[0]
        labels = details["Config"]["Labels"]
        assert labels["com.docker.compose.project"] == args.project
        assert labels["com.docker.compose.service"] == service
        bindings = [entry for rows in details["NetworkSettings"]["Ports"].values() if rows for entry in rows]
        assert bindings == [{"HostIp": "127.0.0.1", "HostPort": port}]

    spec = importlib.util.spec_from_file_location("load", root / "scripts/load-orders.py")
    load = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(load)
    assert load.database(args, "SELECT DATABASE();") == "order_stock"
    assert load.database(args, "SELECT COUNT(*) FROM app_users;") == "0"
    assert load.database(args, "SELECT COUNT(*) FROM purchase_orders;") == "0"
    inventory = load.database(args, "SELECT id,stock FROM products ORDER BY id;")

    def rules_loaded():
        groups = read("/api/v1/rules")["data"]["groups"]
        rules = [rule for group in groups if group["name"] == "stockroom-alerts" for rule in group["rules"]]
        return len(rules) == len(RULES) and {rule["name"] for rule in rules} == RULES and all(rule["health"] == "ok" for rule in rules)

    eventually(rules_loaded)
    eventually(lambda: query('up{job="stockroom"}')[0]["value"][1] == "1")
    eventually(lambda: all(state(name) == "inactive" for name in RULES))
    print("PASS: five rules loaded and inactive", flush=True)
    stopped = False
    try:
        stopped = True
        compose("stop", "app")
        eventually(lambda: state("StockroomAppDown") == "pending")
        print("PASS: app-down alert pending", flush=True)
        eventually(lambda: state("StockroomAppDown") == "firing")
        print("PASS: app-down alert firing", flush=True)
    finally:
        if stopped:
            compose("up", "--no-build", "--no-deps", "--wait", "--wait-timeout", "120", "app")
    eventually(lambda: state("StockroomAppDown") == "inactive")
    print("PASS: app restored and app-down alert resolved", flush=True)
    client = load.Client(BASE)
    assert client.call("/products")[0] == 200
    # The initialized zero series must be scraped before generating the burst.
    eventually(lambda: query('stockroom_auth_requests_total{job="stockroom",endpoint="login",outcome="rate_limited"}'))
    capacity = query('stockroom_auth_limit_capacity{job="stockroom"}')
    assert len(capacity) == 1 and float(capacity[0]["value"][1]) == 10000
    client.csrf = client.call("/auth/csrf")[1]
    credentials = {"email": f"missing-{uuid.uuid4().hex}@example.test", "password": "invalid-test-password"}
    for _ in range(5):
        assert client.call("/auth/login", credentials, form=True)[0] == 401
    for _ in range(25):
        status, body, headers = client.call("/auth/login", credentials, form=True)
        assert status == 429 and body["code"] == "AUTH_RATE_LIMITED" and int(headers["Retry-After"]) > 0
    eventually(lambda: state("StockroomAuthRateLimited") == "pending")
    print("PASS: authentication burst alert pending", flush=True)
    eventually(lambda: state("StockroomAuthRateLimited") == "firing")
    assert state("StockroomServerErrors") == "inactive"
    assert load.database(args, "SELECT id,stock FROM products ORDER BY id;") == inventory
    assert load.database(args, "SELECT COUNT(*) FROM app_users;") == "0"
    assert load.database(args, "SELECT COUNT(*) FROM purchase_orders;") == "0"
    report = {"allChecksPassed": True, "loadedRules": len(RULES),
              "appDownStates": ["inactive", "pending", "firing", "inactive"],
              "authBurstStates": ["inactive", "pending", "firing"],
              "authFailures": 5, "authRateLimited": 25, "inventoryPreserved": True,
              "usersCreated": 0, "ordersCreated": 0}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("PASS: real app-down pending/firing/recovery, auth burst pending/firing, DB preserved")


if __name__ == "__main__":
    main()
