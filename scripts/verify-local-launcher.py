"""Exercise Windows launcher lifecycle using a fresh disposable Compose database only."""
import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--disposable', action='store_true', required=True)
    parser.add_argument('--image', required=True, help='An existing locally built application image')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if os.name != 'nt':
        parser.error('Run this lifecycle check on Windows with Docker Desktop')
    root = Path(__file__).resolve().parents[1]
    project = 'order-stock-launcher-check-' + uuid.uuid4().hex[:12]
    volume = project + '_mysql-data'

    def docker(command, check=True):
        result = subprocess.run(['docker'] + command, capture_output=True, text=True, timeout=240)
        if check and result.returncode:
            raise RuntimeError('Disposable Docker check failed; raw output omitted')
        return result

    image = docker(['image', 'inspect', args.image, '--format', '{{.Id}}']).stdout.strip()
    # Resolve once to an immutable local ID and never pull or rebuild in this check.
    if not image.startswith('sha256:') or docker(['volume', 'inspect', volume], check=False).returncode == 0:
        raise RuntimeError('Disposable image/volume identity check failed')
    docker(['info', '--format', '{{.ServerVersion}}'])
    (root / '.local').mkdir(exist_ok=True)
    checks = []
    with tempfile.TemporaryDirectory(prefix='launcher-check-', dir=root / '.local') as directory:
        private = Path(directory)
        env = private / 'test.env'
        env.write_text('APP_PORT=18086\nMYSQL_PASSWORD=' + secrets.token_hex(24) +
                       '\nMYSQL_ROOT_PASSWORD=' + secrets.token_hex(24) + '\n', encoding='utf-8')
        overlay = private / 'image.yaml'
        overlay.write_text('services:\n  app:\n    image: ' + image + '\n    pull_policy: never\n', encoding='utf-8')
        config = private / 'launcher.json'
        config.write_text(json.dumps({'version': 1, 'project': project, 'envFile': str(env),
                                      'composeFiles': [str(root / 'compose.yaml'), str(overlay)]}), encoding='utf-8')
        compose = ['compose', '-p', project, '--env-file', str(env), '-f', str(root / 'compose.yaml'), '-f', str(overlay)]

        def launcher(action, *extra, success=True):
            command = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                       str(root / 'scripts/local-server.ps1'), '-Config', str(config), '-Action', action, '-NoBrowser', *extra]
            result = subprocess.run(command, cwd=private, capture_output=True, text=True, timeout=300)
            if (result.returncode == 0) != success:
                raise RuntimeError('Launcher lifecycle check failed at ' + action + '; private output omitted')
            return result.stdout

        def sql(statement):
            result = docker(compose + ['exec', '-T', 'db', 'sh', '-c',
                'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" '
                '--batch --skip-column-names --execute="$1"', '--', statement])
            return result.stdout.strip()

        try:
            launcher('start', success=False)
            if docker(['volume', 'inspect', volume], check=False).returncode == 0:
                raise RuntimeError('Missing-volume refusal created a volume')
            checks.append('missingVolumeRefusedWithoutInitialization')
            launcher('start', '-Initialize')
            checks.append('explicitEmptyInstallationStarted')
            if 'Readiness: UP' not in launcher('status'):
                raise RuntimeError('Started application was not ready')
            sql('CREATE TABLE launcher_probe (id INT PRIMARY KEY); INSERT INTO launcher_probe VALUES (142857);')
            before = sql('CHECKSUM TABLE products,launcher_probe EXTENDED;')
            launcher('stop')
            if docker(['volume', 'inspect', volume], check=False).returncode != 0:
                raise RuntimeError('Stop removed the database volume')
            launcher('status', success=False)
            class UnrelatedServer(BaseHTTPRequestHandler):
                def do_GET(self):
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(b'{"status":"UP"}')

                def log_message(self, *_):
                    pass

            # Another process answering UP at the same address must not impersonate this project.
            unrelated = HTTPServer(('127.0.0.1', 18086), UnrelatedServer)
            thread = threading.Thread(target=unrelated.serve_forever, daemon=True)
            thread.start()
            try:
                launcher('status', success=False)
            finally:
                unrelated.shutdown()
                unrelated.server_close()
                thread.join()
            checks.append('unrelatedHealthyServerCannotImpersonateStoppedProject')
            launcher('start')
            if sql('CHECKSUM TABLE products,launcher_probe EXTENDED;') != before:
                raise RuntimeError('Restart did not preserve data')
            checks.extend(['statusReadyAndStopped', 'stopRetainsVolume', 'restartPreservesProductsAndProbe'])
            launcher('start')
            if sql('CHECKSUM TABLE products,launcher_probe EXTENDED;') != before:
                raise RuntimeError('Repeated start changed data')
            checks.append('repeatedStartPreservesData')
        finally:
            # The generated name is never user-supplied. Only this check's volume is removed.
            assert project.startswith('order-stock-launcher-check-') and len(project) == len('order-stock-launcher-check-') + 12
            docker(compose + ['down', '--volumes', '--remove-orphans'])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'passed': True, 'checks': checks, 'disposableStackRemoved': True}, indent=2) + '\n')
    print('PASS: ' + str(len(checks)) + ' launcher lifecycle checks; disposable stack removed')


if __name__ == '__main__':
    main()
