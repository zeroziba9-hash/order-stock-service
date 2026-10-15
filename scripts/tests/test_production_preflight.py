import importlib.util
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('preflight',Path(__file__).resolve().parents[1]/'production-preflight.py')
preflight=importlib.util.module_from_spec(spec);spec.loader.exec_module(preflight)
class ProductionPreflightTests(unittest.TestCase):
    def validate(self,domain='shop.example.com',first='a1'*24,second='b2'*24,extra=''):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/'private.env';file.write_text(f"APP_DOMAIN={domain}\nPROD_MYSQL_PASSWORD='{first}'\nPROD_MYSQL_ROOT_PASSWORD='{second}'\n"+extra);file.chmod(0o600)
            return preflight.validate(file)
    def test_valid_quoted_credentials(self):self.assertEqual(self.validate(),'shop.example.com')
    def test_invalid_hosts(self):
        for host in ('localhost','https://shop.example.com','shop.example.com:443','127.0.0.1','shop.example.com/path','*.example.com'):
            with self.subTest(host=host),self.assertRaises(ValueError):self.validate(domain=host)
    def test_reused_password(self):
        with self.assertRaises(ValueError):self.validate(second='a1'*24)
    def test_short_password(self):
        with self.assertRaises(ValueError):self.validate(first='short')
    def test_duplicate_key(self):
        with self.assertRaises(ValueError):self.validate(extra='APP_DOMAIN=other.example.com\n')
    def test_unquoted_shell_text_is_never_evaluated(self):
        with self.assertRaises(ValueError):self.validate(first='$(echo unsafe)')
if __name__=='__main__':unittest.main()
