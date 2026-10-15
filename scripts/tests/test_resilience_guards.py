import contextlib
import importlib.util
import io
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('resilience',Path(__file__).resolve().parents[1]/'verify-resilience.py')
resilience=importlib.util.module_from_spec(spec);spec.loader.exec_module(resilience)

class ResilienceGuardTests(unittest.TestCase):
    def test_explicit_disposable_flag_is_required_before_container_operations(self):
        with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):
            resilience.arguments(['--image','local-app','--output','report.json'])

    def test_invalid_duration_and_image_are_rejected_without_starting_docker(self):
        for extra in (['--duration-seconds','0'],['--duration-seconds','3601'],['--image','-unsafe'],['--image','app name']):
            with self.subTest(extra=extra),contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):
                resilience.arguments(['--disposable','--image','local-app','--output','report.json']+extra)
