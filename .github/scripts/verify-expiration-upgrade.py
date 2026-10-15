"""Verify V8 -> current schema, including expiration and legacy USER role backfill."""

import argparse
from datetime import datetime, timedelta
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["prepare", "verify"])
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--state-file", required=True, type=Path)
    args = parser.parse_args()
    if (os.environ.get("UPGRADE_DISPOSABLE") != "true" or os.environ.get("APP_PORT") != "18085"
            or os.environ.get("SMOKE_BASE_URL") or os.environ.get("SMOKE_CA_FILE")
            or not args.project.startswith("order-stock-upgrade-") or not args.env_file.is_file()):
        parser.error("Use an isolated order-stock-upgrade-* stack on loopback 18085 with UPGRADE_DISPOSABLE=true")
    root = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("compose_smoke", root / ".github/scripts/compose-smoke.py")
    smoke = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(smoke)
    client = smoke.Client()

    def sql(statement):
        command = ["docker", "compose", "-p", args.project, "--env-file", str(args.env_file.resolve()),
                   "-f", str(root / "compose.yaml"), "exec", "-T", "db", "sh", "-c",
                   'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 '
                   '--user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names']
        result = subprocess.run(command, input=statement + "\n", text=True, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError("Disposable upgrade SQL verification failed")
        return result.stdout.strip()

    assert sql("SELECT DATABASE();") == "order_stock"
    if args.phase == "prepare":
        assert sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;") == "8"
        assert sql("SELECT COUNT(*) FROM app_users;") == "0"
        account = {"email": f"upgrade-{uuid.uuid4().hex}@example.test", "password": secrets.token_hex(24)}
        client.request("/auth/register", data=account, expected=201)
        client.login(account)
        product = client.request("/products")[0]
        payload = {"productId": product["id"], "quantity": 1}
        keys, orders = {}, {}
        for name in ("overdue", "waiting", "paid", "cancelled", "refunded"):
            keys[name] = uuid.uuid4().hex
            orders[name] = client.request("/orders", data=payload, key=keys[name], expected=201)
            assert "expiresAt" not in orders[name], "Baseline must run the actual pre-expiration app"
            path = f"/orders/{orders[name]['orderId']}"
            if name in ("paid", "refunded"):
                client.request(path + "/payments", data={"result": "SUCCESS"}, key=uuid.uuid4().hex, expected=201)
            if name == "refunded":
                client.request(path + "/refunds", data={"result": "SUCCESS"}, key=uuid.uuid4().hex, expected=201)
            if name == "cancelled":
                client.request(path + "/cancel", data={})
        old_ids = ",".join(str(orders[name]["orderId"]) for name in ("overdue", "paid", "cancelled", "refunded"))
        sql(f"UPDATE purchase_orders SET created_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 31 MINUTE) WHERE id IN ({old_ids});")
        snapshots = {name: client.request(f"/orders/{order['orderId']}") for name, order in orders.items()}
        histories = {name: {kind: client.request(f"/orders/{order['orderId']}/{kind}")
                           for kind in ("payments", "refunds")} for name, order in orders.items()}
        args.state_file.parent.mkdir(parents=True, exist_ok=True)
        args.state_file.write_text(json.dumps({"account": account, "product": product, "payload": payload,
                                             "keys": keys, "orders": snapshots, "histories": histories}), encoding="utf-8")
        args.state_file.chmod(0o600)
        print("PASS: V8 app created five real orders and preserved private upgrade fixtures")
    else:
        assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '9' AND success = 1;") == "1"
        assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '10' AND success = 1;") == "1"
        assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '11' AND success = 1;") == "1"
        assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success = 1;") == "1"
        assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '13' AND success = 1;") == "1"
        assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '14' AND success = 1;") == "1"
        assert sql("SELECT COUNT(*) FROM app_users WHERE auth_version <> 0;") == "0"
        for version in ('15','16','17','18'):
            assert sql(f"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '{version}' AND success = 1;") == "1"
        assert sql("SELECT COUNT(*) FROM checkout_quotes;") == "0"
        assert sql("SELECT COUNT(*) FROM saved_carts;") == "0"
        assert sql("SELECT COUNT(*) FROM order_requests WHERE request_hash IS NOT NULL;") == "0"
        assert sql("SELECT COUNT(*) FROM order_request_completions;") == "5"
        assert sql("SELECT COUNT(*) FROM app_sessions;") == "0", "Legacy memory sessions must not be fabricated"
        assert sql("SELECT COUNT(*) FROM app_sessions_ATTRIBUTES;") == "0"
        assert sql("SELECT COUNT(*) FROM catalog_changes;") == "0"
        assert sql("SELECT COUNT(*) FROM products WHERE selling <> TRUE OR catalog_revision <> 0;") == "0"
        assert sql("SELECT COUNT(*) FROM products p WHERE p.stock <> (SELECT COALESCE(SUM(quantity_delta),0) FROM inventory_movements m WHERE m.product_id=p.id);") == "0"
        assert sql("SELECT COUNT(*) FROM app_users WHERE user_role <> 'USER';") == "0"
        assert sql("SELECT COUNT(*) FROM inventory_adjustments;") == "0"
        state = json.loads(args.state_file.read_text(encoding="utf-8"))
        client.login(state["account"])
        assert client.request("/auth/me")["role"] == "USER"
        deadline = time.monotonic() + 15
        overdue_id = state["orders"]["overdue"]["orderId"]
        while client.request(f"/orders/{overdue_id}")["status"] != "EXPIRED" and time.monotonic() < deadline:
            time.sleep(.1)
        for name, original in state["orders"].items():
            path = f"/orders/{original['orderId']}"
            current = client.request(path)
            assert datetime.fromisoformat(current["expiresAt"]) == datetime.fromisoformat(original["createdAt"]) + timedelta(minutes=30)
            if name == "overdue":
                assert current["status"] == "EXPIRED" and current["expiredAt"] == current["cancelledAt"]
                assert datetime.fromisoformat(current["expiredAt"]) >= datetime.fromisoformat(current["expiresAt"])
                for field in ("orderId", "createdAt", "totalAmount", "items", "paidAt", "refundedAt"):
                    assert current[field] == original[field]
                assert client.request(path + "/cancel", data={}) == current
                assert client.request("/orders", data=state["payload"], key=state["keys"][name]) == current
            else:
                assert {k: v for k, v in current.items() if k not in ("expiresAt", "expiredAt")} == original
                assert current["expiredAt"] is None
            for kind in ("payments", "refunds"):
                assert client.request(path + "/" + kind) == state["histories"][name][kind]
        assert client.stock(state["product"]["id"]) == state["product"]["stock"] - 2
        # Runtime window changes apply only to newly created orders; legacy backfill remains 30 minutes.
        new = client.request("/orders", data=state["payload"], key=uuid.uuid4().hex, expected=201)
        assert datetime.fromisoformat(new["expiresAt"]) - datetime.fromisoformat(new["createdAt"]) == timedelta(minutes=7)
        client.request(f"/orders/{new['orderId']}/cancel", data={})
        assert client.stock(state["product"]["id"]) == state["product"]["stock"] - 2
        assert sql("SELECT COUNT(*) FROM products p WHERE p.stock <> (SELECT COALESCE(SUM(quantity_delta),0) FROM inventory_movements m WHERE m.product_id=p.id);") == "0"
        assert sql(f"SELECT COUNT(*) FROM inventory_movements WHERE movement_type='EXPIRE' AND event_reference='{overdue_id}';") == "1"
        print("PASS: V9-V18 migration, historical request completion links preserved, zero initial auth versions, empty initial session store, no fabricated catalog changes, inventory ledger reconciliation, stock restored once, ownership/history/key preservation")


if __name__ == "__main__":
    main()
