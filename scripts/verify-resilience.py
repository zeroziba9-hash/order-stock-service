"""Create an isolated loopback stack; verify restart, DB outage and sustained checkout recovery."""
import argparse
from datetime import datetime
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:18089'


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--disposable', action='store_true', required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--duration-seconds', type=int, default=300)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    if not 60 <= args.duration_seconds <= 3600:
        parser.error('duration must be 60..3600 seconds')
    if not args.image or any(c.isspace() for c in args.image) or args.image.startswith('-'):
        parser.error('a locally built application image is required')
    return args


class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, path, data=None, method='GET', key=None, form=False, expected=200):
        headers = {'Accept': 'application/json'}
        if method != 'GET':
            csrf = self.request('/auth/csrf')
            headers[csrf['headerName']] = csrf['token']
        if key:
            headers['Idempotency-Key'] = key
        if data is not None:
            headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            data = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
        try:
            response = self.opener.open(urllib.request.Request(BASE + path, data, headers, method=method), timeout=15)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            body = response.read()
            if response.status != expected:
                raise RuntimeError(f'Unexpected HTTP status {response.status} at {path.split("?")[0]}')
            return json.loads(body) if body else None


def wait_ready(expected=200, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            try:
                response = urllib.request.urlopen(BASE + '/actuator/health/readiness', timeout=3)
            except urllib.error.HTTPError as failure:
                response = failure
            with response:
                if response.status == expected:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise RuntimeError('Readiness did not reach the expected state')


def main():
    args = arguments()
    project = 'order-stock-resilience-' + uuid.uuid4().hex[:12]
    report = {'passed': False, 'publicDeploymentVerified': False, 'externalBackupRestoreVerified': False}
    phase = 'prepare'
    with tempfile.TemporaryDirectory(prefix='stockroom-resilience-') as folder:
        private = Path(folder)
        envfile = private / 'test.env'
        envfile.write_text(f'APP_PORT=18089\nMYSQL_PASSWORD={secrets.token_hex(24)}\nMYSQL_ROOT_PASSWORD={secrets.token_hex(24)}\n', encoding='utf-8')
        os.chmod(envfile, 0o600)
        overlay = private / 'image.yaml'
        overlay.write_text('services:\n  app:\n    image: ' + json.dumps(args.image) + '\n    environment:\n      APP_ORDERS_EXPIRATION_POLL_INTERVAL: PT1S\n      SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT: "2000"\n', encoding='utf-8')
        compose = ['docker', 'compose', '-p', project, '--env-file', str(envfile), '-f', str(ROOT / 'compose.yaml'), '-f', str(overlay)]

        def run(command):
            result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=300)
            if result.returncode:
                raise RuntimeError('Isolated container operation failed')
            return result.stdout.strip()

        def sql(query):
            return run(compose + ['exec', '-T', 'db', 'sh', '-c',
                'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names --execute="$1"', '--', query])

        owned = False
        try:
            # Never attach to an existing stack or volume, even on an accidental project collision.
            if run(['docker', 'ps', '-aq', '--filter', f'label=com.docker.compose.project={project}']):
                raise RuntimeError('Fresh project required')
            if run(['docker', 'volume', 'ls', '-q', '--filter', f'label=com.docker.compose.project={project}']):
                raise RuntimeError('Fresh volume required')
            owned = True
            run(compose + ['up', '--no-build', '--wait', '--wait-timeout', '240'])
            client = Client()
            account = {'email': f'resilience-{uuid.uuid4().hex}@example.test', 'password': secrets.token_hex(20)}
            client.request('/auth/register', account, 'POST', expected=201)
            identity = client.request('/auth/login', account, 'POST', form=True)
            original = client.request('/products')
            items = [{'productId': p['id'], 'quantity': 1} for p in original]
            basket = client.request('/cart', {'revision': 0, 'items': items}, 'PUT')
            quote = client.request('/orders/cart/quote', {'items': items}, 'POST')
            body, key = {'quoteId': quote['quoteId']}, uuid.uuid4().hex
            order = client.request('/orders/cart/checkout', body, 'POST', key, expected=201)
            phase = 'abrupt-restart'
            run(compose + ['kill', '-s', 'SIGKILL', 'app'])
            run(compose + ['up', '--no-build', '--no-deps', '--wait', '--wait-timeout', '120', 'app'])
            assert client.request('/auth/me') == identity
            assert client.request('/cart')['revision'] == basket['revision']
            assert client.request('/cart')['items'] and client.request('/orders/cart/checkout', body, 'POST', key) == order
            for old, current in zip(original, client.request('/products')):
                assert current['id'] == old['id'] and current['stock'] == old['stock'] - 1
            report['abruptRestartSessionCartAndReplay'] = True
            print('PASS: abrupt app restart retains session, cart and committed checkout replay', flush=True)

            phase = 'database-outage'
            outage_quote = client.request('/orders/cart/quote', {'items': items}, 'POST')
            outage_body, outage_key = {'quoteId': outage_quote['quoteId']}, uuid.uuid4().hex
            run(compose + ['stop', '-t', '10', 'db'])
            wait_ready(expected=503, timeout=60)
            try:
                client.request('/orders/cart/checkout', outage_body, 'POST', outage_key, expected=201)
            except (RuntimeError, OSError, urllib.error.URLError):
                pass
            else:
                raise RuntimeError('Checkout unexpectedly succeeded with DB stopped')
            run(compose + ['up', '--no-build', '--wait', '--wait-timeout', '180', 'db'])
            wait_ready()
            assert client.request('/auth/me') == identity
            recovered = client.request('/orders/cart/checkout', outage_body, 'POST', outage_key, expected=201)
            assert client.request('/orders/cart/checkout', outage_body, 'POST', outage_key) == recovered
            client.request(f"/orders/{recovered['orderId']}/cancel", {}, 'POST')
            client.request(f"/orders/{order['orderId']}/cancel", {}, 'POST')
            report['databaseOutageReadinessAndRetry'] = True
            print('PASS: DB outage marks readiness down; original request succeeds once after recovery', flush=True)

            phase = 'expiration-during-downtime'
            normal_overlay = overlay.read_text(encoding='utf-8')
            overlay.write_text(normal_overlay + '      APP_ORDERS_EXPIRATION_PAYMENT_WINDOW: PT5S\n', encoding='utf-8')
            run(compose + ['up', '--no-build', '--no-deps', '--wait', '--wait-timeout', '120', 'app'])
            expired_quote = client.request('/orders/cart/quote', {'items': items}, 'POST')
            expiring = client.request('/orders/cart/checkout', {'quoteId': expired_quote['quoteId']}, 'POST', uuid.uuid4().hex, expected=201)
            run(compose + ['stop', '-t', '10', 'app'])
            # Let the real fixed deadline pass while the app is stopped; do not rewrite order timestamps.
            while time.time() <= datetime.fromisoformat(expiring['expiresAt'].replace('Z', '+00:00')).timestamp() + 1:
                time.sleep(1)
            overlay.write_text(normal_overlay, encoding='utf-8')
            run(compose + ['up', '--no-build', '--no-deps', '--wait', '--wait-timeout', '120', 'app'])
            deadline = time.monotonic() + 30
            while client.request(f"/orders/{expiring['orderId']}")['status'] != 'EXPIRED':
                if time.monotonic() > deadline:
                    raise RuntimeError('Overdue order was not recovered')
                time.sleep(1)
            client.request(f"/orders/{expiring['orderId']}/cancel", {}, 'POST')
            assert sql(f"SELECT COUNT(*) FROM inventory_movements WHERE movement_type='EXPIRE' AND event_reference='{int(expiring['orderId'])}'") == str(len(items))
            report['overdueOrderRecoveryExactlyOnce'] = True
            print('PASS: order overdue during downtime expires once and restores every item', flush=True)

            phase = 'sustained-checkout'
            started = time.monotonic()
            rounds = 0
            while time.monotonic() - started < args.duration_seconds:
                q = client.request('/orders/cart/quote', {'items': items}, 'POST')
                payload, request_key = {'quoteId': q['quoteId']}, uuid.uuid4().hex
                created = client.request('/orders/cart/checkout', payload, 'POST', request_key, expected=201)
                assert client.request('/orders/cart/checkout', payload, 'POST', request_key) == created
                client.request(f"/orders/{created['orderId']}/cancel", {}, 'POST')
                rounds += 1
                # Retain rate below per-account quote quota. This is a soak check, not a throughput benchmark.
                if rounds % 20 == 0:
                    print(f'CHECK: sustained checkout round {rounds}', flush=True)
                time.sleep(7)
            report['sustainedSeconds'] = round(time.monotonic() - started, 2)
            report['sustainedCheckoutRounds'] = rounds
            report['measuredThroughputBenchmark'] = False
            for old, current in zip(original, client.request('/products')):
                assert current['id'] == old['id'] and current['stock'] == old['stock']
            assert sql('SELECT COUNT(*) FROM products p WHERE p.stock<>(SELECT COALESCE(SUM(quantity_delta),0) FROM inventory_movements m WHERE m.product_id=p.id)') == '0'
            assert sql('SELECT COUNT(*) FROM order_requests r LEFT JOIN order_request_completions c ON c.request_id=r.id WHERE c.request_id IS NULL') == '0'
            report['inventoryAndRequestReconciliation'] = True
            report['passed'] = True
            print('PASS: sustained checkout, replay and cancellation reconcile inventory and request links', flush=True)
        except Exception:
            report['failedPhase'] = phase
            raise RuntimeError(f'Resilience verification failed during {phase}; private HTTP/DB data omitted') from None
        finally:
            # This script creates the name itself; no existing user's project name is accepted.
            if not project.startswith('order-stock-resilience-') or len(project) != 35:
                raise RuntimeError('Unexpected cleanup target')
            if owned:
                run(compose + ['down', '--volumes', '--remove-orphans'])
                report['disposableStackRemoved'] = True
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
