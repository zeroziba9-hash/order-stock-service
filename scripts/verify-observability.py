"""Verify a disposable monitoring stack after load-orders.py (100 requests)."""

import argparse
import importlib.util
import json
import math
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def read(url):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url, timeout=10) as response:
        return json.load(response)


def query(expression):
    result = read("http://127.0.0.1:19090/api/v1/query?" + urllib.parse.urlencode({"query": expression}))
    assert result["status"] == "success", "Prometheus query failed"
    return result["data"]["result"]


def sample(expression):
    rows = query(expression)
    assert len(rows) == 1, f"Expected one metric for {expression}"
    return float(rows[0]["value"][1])


def eventually(probe, seconds=75):
    deadline = time.monotonic() + seconds
    while True:
        try:
            result = probe()
            if result:
                return result
        except (AssertionError, OSError, ValueError):
            pass
        if time.monotonic() >= deadline:
            raise AssertionError("Monitoring probe did not converge; inspect the disposable stack")
        time.sleep(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if (os.environ.get("LOAD_DISPOSABLE") != "true" or not args.project.startswith("order-stock-load-")
            or not args.env_file.is_file()):
        parser.error("Requires LOAD_DISPOSABLE=true and an order-stock-load-* disposable project")
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location("load_orders", root / "scripts/load-orders.py")
    load = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(load)
    assert load.database(args, "SELECT DATABASE();") == "order_stock"
    # Permit a read-only retry after this verifier has already expired its fixture.
    initial_count = int(load.database(args, "SELECT COUNT(*) FROM purchase_orders;"))
    assert initial_count in [11, 12]
    assert load.database(args, "SELECT COUNT(*) FROM purchase_orders WHERE status = 'CANCELLED';") == "11"
    base = "http://127.0.0.1:18084"
    try:
        urllib.request.build_opener(urllib.request.ProxyHandler({})).open(base + "/actuator/prometheus", timeout=10)
        raise AssertionError("Main application port exposed Prometheus")
    except urllib.error.HTTPError as error:
        assert error.code == 403
    eventually(lambda: sample('up{job="stockroom"}') == 1)
    def events(outcome):
        return sample('stockroom_order_events_total{job="stockroom",outcome="' + outcome + '"}')
    eventually(lambda: events("created") == initial_count and events("replayed") == 99
               and events("cancelled") == 11 and events("expired") == initial_count - 11)
    assert sample('stockroom_order_rejections_total{job="stockroom",reason="insufficient_stock"}') == 90
    assert sample('stockroom_expiration_failures_total{job="stockroom"}') == 0

    client = load.Client(base)
    if initial_count == 11:
        client.login()
        status, order, _ = client.call("/orders", {"productId": 1, "quantity": 1}, uuid.uuid4().hex)
        assert status == 201
        order_id = int(order["orderId"])
        # Only this verifier's unpaid order is aged. UTC matches JDBC timestamps;
        # created_at is moved too so expires_at > created_at remains true.
        load.database(args, f"UPDATE purchase_orders SET created_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 31 MINUTE), "
                           f"expires_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE) "
                           f"WHERE id={order_id} AND status='CREATED';")
        eventually(lambda: client.call(f"/orders/{order_id}")[1]["status"] == "EXPIRED")
        expired = client.call(f"/orders/{order_id}")[1]
        assert expired["expiredAt"] == expired["cancelledAt"]
    eventually(lambda: events("created") == 12 and events("expired") == 1)
    assert client.call("/products")[1][0]["stock"] == 10
    # Cancellation is not called: EXPIRED rejects cancellation by contract.
    assert load.database(args, "SELECT COUNT(*) FROM purchase_orders WHERE status='EXPIRED';") == "1"
    assert sample('stockroom_expiration_failures_total{job="stockroom"}') == 0

    dashboard = json.loads((root / "monitoring/grafana/dashboards/stockroom.json").read_text(encoding="utf-8"))
    for panel in dashboard["panels"]:
        for target in panel["targets"]:
            assert query(target["expr"]), f"No data for dashboard panel {panel['title']}"
    latency_query = ('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket'
                     '{job="stockroom",application="order-stock-service",uri!~"/actuator.*"}[1m])))')
    eventually(lambda: math.isfinite(sample(latency_query)))
    latency = sample(latency_query)
    assert latency >= 0
    assert query('hikaricp_connections_active{job="stockroom"}')
    assert query('jvm_memory_used_bytes{job="stockroom",area="heap"}')
    grafana = "http://127.0.0.1:13000"
    provisioned = eventually(lambda: read(grafana + "/api/dashboards/uid/stockroom"))
    assert provisioned["dashboard"]["uid"] == "stockroom"
    assert len(provisioned["dashboard"]["panels"]) == len(dashboard["panels"])
    assert provisioned["meta"]["canSave"] is False
    assert provisioned["meta"]["canEdit"] is False
    proxy = read(grafana + "/api/datasources/proxy/uid/stockroom-prometheus/api/v1/query?"
                 + urllib.parse.urlencode({"query": 'up{job="stockroom"}'}))
    assert float(proxy["data"]["result"][0]["value"][1]) == 1
    report = {"format": "stockroom-monitoring-v1", "allChecksPassed": True,
              "committedEvents": {outcome: events(outcome) for outcome in ["created", "replayed", "cancelled", "expired"]},
              "insufficientStockResponses": 90, "expirationRollbacks": 0,
              "dashboardPanels": len(dashboard["panels"]), "serverHistogramP95Seconds": latency,
              "mainPortMetricsDenied": True, "grafanaAnonymousViewer": True, "finalStock": 10}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("PASS: committed counters, scheduled expiration, all dashboard queries and Grafana Viewer")


if __name__ == "__main__":
    main()
