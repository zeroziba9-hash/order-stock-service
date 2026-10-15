import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('benchmark',Path(__file__).resolve().parents[1]/'benchmark-orders.py')
benchmark=importlib.util.module_from_spec(spec);spec.loader.exec_module(benchmark)
class BenchmarkDiagnosticTests(unittest.TestCase):
    def test_success_does_not_create_failure_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory)/'report.json'
            benchmark.require_responses([{'status':201}],201,'warmup',{},output)
            self.assertFalse((output.parent/'benchmark-failure.json').exists())
    def test_failure_keeps_status_code_uuid_but_drops_body_and_headers(self):
        trace='12345678-1234-1234-1234-123456789abc'
        rows=[{'status':409,'body':{'code':'ORDER_CONFLICT','message':'private message','password':'private password'},'headers':{'X-Request-ID':trace,'Set-Cookie':'private cookie'}}]
        with tempfile.TemporaryDirectory() as directory,contextlib.redirect_stdout(io.StringIO()) as stdout:
            output=Path(directory)/'report.json'
            with self.assertRaises(AssertionError):benchmark.require_responses(rows,201,'measured',{'mode':'locking'},output)
            text=(output.parent/'benchmark-failure.json').read_text()
            evidence=json.loads(text);self.assertEqual(evidence['statusCounts'],{'409':1});self.assertEqual(evidence['failures'][0],{'status':409,'code':'ORDER_CONFLICT','requestId':trace})
            self.assertNotIn('private',text+stdout.getvalue())
    def test_untrusted_code_and_trace_are_not_copied(self):
        rows=[{'status':500,'body':{'code':'secret@email.test'},'headers':{'X-Request-ID':'untrusted trace'}}]
        with tempfile.TemporaryDirectory() as directory,contextlib.redirect_stdout(io.StringIO()):
            output=Path(directory)/'report.json'
            with self.assertRaises(AssertionError):benchmark.require_responses(rows,201,'warmup',{},output)
            evidence=json.loads((output.parent/'benchmark-failure.json').read_text())
            self.assertEqual(evidence['failures'][0],{'status':500,'code':'UNCLASSIFIED','requestId':None})
if __name__=='__main__':unittest.main()
