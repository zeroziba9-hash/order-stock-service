import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release_manifest', Path(__file__).resolve().parents[1] / 'release-manifest.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseManifestTests(unittest.TestCase):
    def result(self, value):
        return subprocess.CompletedProcess([], 0, value, '')

    def test_dirty_source_is_rejected_before_image_inspection(self):
        with patch.object(release.subprocess, 'run', return_value=self.result(' M src/changed.js')) as run:
            with self.assertRaises(ValueError):
                release.collect(Path('.'), 'v0.1.0-rc.1', 'local-image')
            self.assertEqual(run.call_count, 1)

    def test_mismatched_image_revision_is_rejected(self):
        responses = ['', 'a' * 40, json.dumps([{'Id': 'sha256:local', 'Config': {'Labels': {'org.opencontainers.image.revision': 'b' * 40}}}])]
        with patch.object(release.subprocess, 'run', side_effect=[self.result(value) for value in responses]):
            with self.assertRaises(ValueError):
                release.collect(Path('.'), 'v0.1.0-rc.1', 'local-image')

    def test_identity_report_cannot_claim_external_validation_or_export_environment(self):
        metadata = [{'Id': 'sha256:local', 'Config': {'Labels': {'org.opencontainers.image.revision': 'a' * 40}, 'Env': ['PRIVATE=do-not-export']}}]
        responses = ['', 'a' * 40, json.dumps(metadata), 'c' * 40]
        with patch.object(release.subprocess, 'run', side_effect=[self.result(value) for value in responses]):
            report = release.collect(Path('.'), 'v0.1.0-rc.1', 'local-image')
        for key in ['releasePublished', 'publicDeploymentVerified', 'externalMailDeliveryVerified', 'externalBackupRestoreVerified', 'realIosSafariVerified']:
            self.assertFalse(report[key])
        self.assertNotIn('do-not-export', json.dumps(report))
