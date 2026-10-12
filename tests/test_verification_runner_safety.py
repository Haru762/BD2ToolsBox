"""Runner must reject unsafe report destinations before any test side effect."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

RUNNER = Path(__file__).resolve().parents[1] / 'tools' / 'run_python_tests.py'


class VerificationRunnerSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='bd2-runner-safety-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'repo'
        self.tests = self.root / 'tests'
        self.tests.mkdir(parents=True)
        self.marker = self.root / 'executed'
        (self.tests / 'test_sample.py').write_text(
            'import unittest\nfrom pathlib import Path\n'
            'class Sample(unittest.TestCase):\n'
            ' def test_sample(self):\n'
            f'  Path({str(self.marker)!r}).write_text("executed")\n'
            '  self.assertEqual(2, 1 + 1)\n', encoding='utf8')
        self.manifest = self.root / 'fixtures.json'
        self.manifest.write_text(json.dumps({'fixtures': []}), encoding='utf8')

    def run_runner(self, output, *extra_args):
        return subprocess.run([
            sys.executable, '-B', str(RUNNER), '--root', str(self.root),
            '--start-dir', str(self.tests), '--fixture-manifest', str(self.manifest),
            '--environment', str(self.root / 'absent-env.json'), '--output', str(output),
            *extra_args
        ], capture_output=True, text=True, encoding='utf8')

    def directory_link(self, link, target):
        link.parent.mkdir(parents=True, exist_ok=True)
        if os.name == 'nt':
            result = subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(target)],
                                    capture_output=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        else:
            link.symlink_to(target, target_is_directory=True)
        self.addCleanup(self.remove_link, link)

    @staticmethod
    def remove_link(link):
        if os.path.lexists(link):
            if os.name == 'nt':
                os.rmdir(link)
            else:
                link.unlink()

    def test_outside_existing_file_is_not_overwritten(self):
        output = self.base / 'outside.json'
        output.write_bytes(b'KEEP ORIGINAL')
        result = self.run_runner(output)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b'KEEP ORIGINAL', output.read_bytes())
        self.assertFalse(self.marker.exists())

    def test_outside_parent_is_not_created(self):
        output = self.base / 'outside-directory' / 'report.json'
        result = self.run_runner(output)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.parent.exists())
        self.assertFalse(self.marker.exists())

    def test_existing_inside_file_is_not_overwritten_or_used_as_a_report(self):
        output = self.root / 'existing.json'
        output.write_bytes(b'KEEP EXISTING EVIDENCE')
        result = self.run_runner(output)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b'KEEP EXISTING EVIDENCE', output.read_bytes())
        self.assertFalse(self.marker.exists())

    def test_new_report_cannot_be_created_in_the_source_tree(self):
        output = self.root / 'app' / 'src' / 'report.json'
        result = self.run_runner(output)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.parent.exists())
        self.assertFalse(self.marker.exists())

    def test_external_directory_link_is_rejected_before_discovery(self):
        outside = self.base / 'outside'
        outside.mkdir()
        link = self.root / 'local' / 'linked'
        self.directory_link(link, outside)
        result = self.run_runner(link / 'report.json')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], list(outside.iterdir()))
        self.assertFalse(self.marker.exists())

    def test_internal_directory_link_cannot_redirect_a_report_into_sources(self):
        source = self.root / 'app' / 'src'
        source.mkdir(parents=True)
        link = self.root / 'local' / 'linked'
        self.directory_link(link, source)
        result = self.run_runner(link / 'report.json')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], list(source.iterdir()))
        self.assertFalse(self.marker.exists())

    def test_link_created_by_tests_cannot_create_any_outside_report_directories(self):
        outside = self.base / 'outside'
        outside.mkdir()
        link = self.root / 'local'
        self.addCleanup(self.remove_link, link)
        if os.name == 'nt':
            action = f'subprocess.run(["cmd", "/c", "mklink", "/J", {str(link)!r}, {str(outside)!r}], check=True, capture_output=True)'
        else:
            action = f'Path({str(link)!r}).symlink_to({str(outside)!r}, target_is_directory=True)'
        (self.tests / 'test_sample.py').write_text(
            'import unittest, subprocess\nfrom pathlib import Path\n'
            'class Sample(unittest.TestCase):\n'
            ' def test_sample(self):\n'
            f'  Path({str(self.marker)!r}).write_text("executed")\n'
            f'  {action}\n', encoding='utf8')
        result = self.run_runner(link / 'new-directory' / 'report.json')
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(self.marker.exists())
        self.assertEqual([], list(outside.iterdir()), 'Even mkdir must be gated before touching outside paths')

    def test_a_report_created_during_tests_is_never_truncated(self):
        output = self.root / 'local' / 'report.json'
        (self.tests / 'test_sample.py').write_text(
            'import unittest\nfrom pathlib import Path\n'
            'class Sample(unittest.TestCase):\n'
            ' def test_sample(self):\n'
            f'  p = Path({str(output)!r})\n'
            '  p.parent.mkdir(parents=True)\n'
            '  p.write_bytes(b"KEEP CONCURRENT FILE")\n', encoding='utf8')
        result = self.run_runner(output)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b'KEEP CONCURRENT FILE', output.read_bytes())

    def test_allow_skip_cannot_waive_a_required_test(self):
        (self.tests / 'test_sample.py').write_text(
            'import unittest\nclass Sample(unittest.TestCase):\n'
            ' @unittest.skip("unavailable")\n def test_sample(self):\n  pass\n', encoding='utf8')
        output = self.root / 'local' / 'report.json'
        result = self.run_runner(output, '--allow-skip', 'test_sample.Sample.test_sample')
        self.assertNotEqual(0, result.returncode)
        report = json.loads(output.read_text(encoding='utf8'))
        self.assertEqual('FAIL', report['result'])
        self.assertEqual(1, report['counts']['skipped'])
        self.assertIn('required-test-skipped', [reason['code'] for reason in report['reasons']])

    def test_expected_failure_is_not_required_coverage(self):
        (self.tests / 'test_sample.py').write_text(
            'import unittest\nclass Sample(unittest.TestCase):\n'
            ' @unittest.expectedFailure\n def test_sample(self):\n  self.fail("known bug")\n', encoding='utf8')
        output = self.root / 'local' / 'report.json'
        result = self.run_runner(output)
        self.assertNotEqual(0, result.returncode)
        report = json.loads(output.read_text(encoding='utf8'))
        self.assertEqual('FAIL', report['result'])
        self.assertEqual(0, report['counts']['passed'])
        self.assertEqual('expected-failure', report['tests'][0]['outcome'])

    def test_failed_subtests_count_the_parent_once(self):
        (self.tests / 'test_sample.py').write_text(
            'import unittest\nclass Sample(unittest.TestCase):\n'
            ' def test_sample(self):\n'
            '  for value in (1, 2):\n'
            '   with self.subTest(value=value):\n'
            '    self.fail("subtest failure")\n', encoding='utf8')
        output = self.root / 'local' / 'report.json'
        result = self.run_runner(output)
        self.assertNotEqual(0, result.returncode)
        report = json.loads(output.read_text(encoding='utf8'))
        self.assertEqual('FAIL', report['result'])
        self.assertEqual(dict(discovered=1, executed=1, passed=0, failed=1,
                              errors=0, skipped=0, notRun=0), report['counts'])
        self.assertEqual(1, len(report['tests']))
        self.assertEqual('testcase', report['tests'][0]['source'])
        self.assertEqual(2, len(report['tests'][0]['messages']))

    def test_errored_subtests_count_the_parent_once(self):
        (self.tests / 'test_sample.py').write_text(
            'import unittest\nclass Sample(unittest.TestCase):\n'
            ' def test_sample(self):\n'
            '  with self.subTest(value=1):\n'
            '   raise ValueError("subtest error")\n', encoding='utf8')
        output = self.root / 'local' / 'report.json'
        result = self.run_runner(output)
        self.assertNotEqual(0, result.returncode)
        report = json.loads(output.read_text(encoding='utf8'))
        self.assertEqual('FAIL', report['result'])
        self.assertEqual(dict(discovered=1, executed=1, passed=0, failed=0,
                              errors=1, skipped=0, notRun=0), report['counts'])
        self.assertEqual(1, len(report['tests']))
        self.assertEqual('testcase', report['tests'][0]['source'])
        self.assertIn('ValueError: subtest error', report['tests'][0]['messages'][0])

    def test_class_setup_errors_remain_visible(self):
        (self.tests / 'test_sample.py').write_text(
            'import unittest\nclass Sample(unittest.TestCase):\n'
            ' @classmethod\n def setUpClass(cls):\n'
            '  raise RuntimeError("setup error")\n'
            ' def test_sample(self):\n  self.fail("must not run")\n', encoding='utf8')
        output = self.root / 'local' / 'report.json'
        result = self.run_runner(output)
        self.assertNotEqual(0, result.returncode)
        report = json.loads(output.read_text(encoding='utf8'))
        self.assertEqual('FAIL', report['result'])
        self.assertEqual(1, report['counts']['errors'])
        self.assertEqual(1, report['counts']['notRun'])
        setup_errors = [test for test in report['tests'] if test['source'] == 'fixture-setup']
        self.assertEqual(1, len(setup_errors))
        self.assertIn('RuntimeError: setup error', setup_errors[0]['messages'][0])

    def test_safe_new_report_runs_real_tests_and_records_counts(self):
        output = self.root / 'local' / 'results' / 'report.json'
        result = self.run_runner(output)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(self.marker.is_file())
        report = json.loads(output.read_text(encoding='utf8'))
        self.assertEqual('PASS', report['result'])
        self.assertEqual(1, report['counts']['passed'])
        self.assertEqual(0, report['counts']['skipped'])


if __name__ == '__main__':
    unittest.main()
