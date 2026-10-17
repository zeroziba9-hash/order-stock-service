"""Record a clean commit and a revision-labelled local image; never publishes a release."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess


def collect(root, version, image):
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-rc\.[1-9]\d*)?', version):
        raise ValueError('Use a version such as v0.1.0-rc.1')

    def command(args):
        result = subprocess.run(args, cwd=root, capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise ValueError('Could not read Git or the local image; raw output omitted')
        return result.stdout.strip()

    if command(['git', 'status', '--porcelain']):
        raise ValueError('Commit the reviewed source before recording a release candidate')
    commit = command(['git', 'rev-parse', 'HEAD'])
    metadata = json.loads(command(['docker', 'image', 'inspect', image]))[0]
    revision = (metadata['Config'].get('Labels') or {}).get('org.opencontainers.image.revision')
    if revision != commit:
        raise ValueError('Build an image labelled with the exact clean HEAD revision first')
    return {'version': version, 'recordedAtUtc': datetime.now(timezone.utc).isoformat(),
            'sourceCommit': commit, 'sourceTree': command(['git', 'rev-parse', 'HEAD^{tree}']),
            'imageId': metadata['Id'], 'imageRevisionLabel': revision,
            'note': 'Local candidate identity only. A revision label alone is not build provenance or test evidence.',
            'releasePublished': False, 'publicDeploymentVerified': False,
            'externalMailDeliveryVerified': False, 'externalBackupRestoreVerified': False,
            'realIosSafariVerified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    report = collect(Path(__file__).resolve().parents[1], args.version, args.image)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('PASS: local candidate identity recorded; no tag, release or deployment created')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError):
        raise SystemExit('FAIL: use clean committed source and an exact revision-labelled local image')
