import unittest
import json
import hashlib
import tempfile
import os
import shutil
import subprocess
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tools.acceptance.run_t08_game_recovery import validate_config, _sha

class TestT08AcceptanceSafety(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp()
        self.root_dir = Path(self.tmp_dir)
        self.local_dir = self.root_dir / "local"
        self.local_dir.mkdir()
        self.run_root = self.local_dir / "test-run"
        self.run_root.mkdir()
        
        self.bundle_name = "a" * 32
        self.hash_dir = "b" * 32
        
        self.original_data = b"original"
        self.replacement_data = b"replacement"
        
        (self.run_root / "original-game-data").write_bytes(self.original_data)
        (self.run_root / "replacement-game-data").write_bytes(self.replacement_data)
        
        self.valid_config = {
            "bundleName": self.bundle_name,
            "hashDir": self.hash_dir,
            "expectedAppAndroidId": "1234567890abcdef",
            "oldSha256": _sha(self.original_data),
            "newSha256": _sha(self.replacement_data),
            "oldBytes": len(self.original_data),
            "newBytes": len(self.replacement_data),
            "remote": f"/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/{self.bundle_name}/{self.hash_dir}/__data"
        }
        
        self._write_config(self.valid_config)

    def _write_config(self, cfg):
        (self.run_root / "game-fixture.json").write_text(json.dumps(cfg), encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.tmp_dir)

    def test_invalid_args_mode(self):
        with self.assertRaisesRegex(ValueError, "invalid args_mode"):
            validate_config("invalid", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)

    def test_missing_allow_flag(self):
        with self.assertRaisesRegex(ValueError, "Missing explicit allow game write flag"):
            validate_config("converted", str(self.run_root), "INVALID", self.root_dir)

    def test_run_root_outside_local(self):
        outside_dir = self.root_dir / "outside"
        outside_dir.mkdir()
        with self.assertRaisesRegex(ValueError, "run-root must be within local directory"):
            validate_config("converted", str(outside_dir), "SINGLE_BACKED_UP_TARGET", self.root_dir)

    def test_traversal_symlink_escape(self):
        outside_dir = self.root_dir / "outside"
        outside_dir.mkdir()
        
        symlink_dir = self.local_dir / "symlink"
        try:
            os.symlink(str(outside_dir), str(symlink_dir))
        except OSError:
            subprocess.run(["cmd", "/c", "mklink", "/J", str(symlink_dir), str(outside_dir)], check=True)
            
        with self.assertRaisesRegex(ValueError, "run-root must be within local directory"):
            validate_config("converted", str(symlink_dir), "SINGLE_BACKED_UP_TARGET", self.root_dir)


    def test_local_prefix_sibling(self):
        sibling_dir = self.root_dir / "local-evil"
        sibling_dir.mkdir()
        with self.assertRaisesRegex(ValueError, "run-root must be within local directory"):
            validate_config("converted", str(sibling_dir), "SINGLE_BACKED_UP_TARGET", self.root_dir)

    def test_malformed_bundle_hash(self):
        cfg = dict(self.valid_config)
        cfg["bundleName"] = "invalid"
        self._write_config(cfg)
        with self.assertRaisesRegex(ValueError, "malformed bundleName"):
            validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)
            
        cfg["bundleName"] = self.bundle_name
        cfg["hashDir"] = "invalid"
        self._write_config(cfg)
        with self.assertRaisesRegex(ValueError, "malformed hashDir"):
            validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)

    def test_expectedAppAndroidId_missing(self):
        cfg = dict(self.valid_config)
        del cfg["expectedAppAndroidId"]
        self._write_config(cfg)
        with self.assertRaisesRegex(ValueError, "expectedAppAndroidId missing or malformed"):
            validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)

    def test_replacement_equals_original(self):
        cfg = dict(self.valid_config)
        cfg["newSha256"] = cfg["oldSha256"]
        self._write_config(cfg)
        with self.assertRaisesRegex(ValueError, "replacement equals original"):
            validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)

    def test_sha_mismatch(self):
        cfg = dict(self.valid_config)
        cfg["oldSha256"] = "bad"
        self._write_config(cfg)
        with self.assertRaisesRegex(ValueError, "original-game-data SHA mismatch"):
            validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)

        cfg["oldSha256"] = _sha(self.original_data)
        cfg["newSha256"] = "bad"
        self._write_config(cfg)
        with self.assertRaisesRegex(ValueError, "replacement-game-data SHA mismatch"):
            validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)

    def test_remote_prefix_only(self):
        cfg = dict(self.valid_config)
        cfg["remote"] = f"/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/something_else"
        self._write_config(cfg)
        with self.assertRaisesRegex(ValueError, "malformed remote target"):
            validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)

    def test_bytes_validation(self):
        for key in ("oldBytes", "newBytes"):
            # missing
            cfg = dict(self.valid_config)
            del cfg[key]
            self._write_config(cfg)
            with self.assertRaisesRegex(ValueError, f"{key} is missing"):
                validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)
            
            # bool
            cfg = dict(self.valid_config)
            cfg[key] = True
            self._write_config(cfg)
            with self.assertRaisesRegex(ValueError, f"{key} must be a positive int"):
                validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)
            
            # non-positive
            cfg = dict(self.valid_config)
            cfg[key] = 0
            self._write_config(cfg)
            with self.assertRaisesRegex(ValueError, f"{key} must be a positive int"):
                validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)
            
            # not equal
            cfg = dict(self.valid_config)
            cfg[key] = 9999
            self._write_config(cfg)
            with self.assertRaisesRegex(ValueError, f"{key} must equal the length of the data"):
                validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)

    def test_path_resolution_boundary_test(self):
        # A separate deterministic path-resolution boundary test for an input file resolving outside run-root.
        # Monkeypatching Path.resolve is acceptable. The test name and comment must say it is a boundary test.
        # It must not skip and must not hide the assertion when symlink creation is unavailable.
        from unittest.mock import patch
        
        outside_file = self.root_dir / "outside_file"
        
        with patch.object(Path, "resolve", autospec=True) as mock_resolve:
            # We mock the resolve calls inside validate_config
            # The calls are:
            # 1. run_root = Path(args_run_root).resolve()
            # 2. local_dir = (root_dir / "local").resolve()
            # 3. game_fixture = (run_root / "game-fixture.json").resolve()
            # 4. original_data = (run_root / "original-game-data").resolve()
            # 5. replacement_data = (run_root / "replacement-game-data").resolve()
            def fake_resolve(self_path_obj, *args, **kwargs):
                path_str = str(self_path_obj)
                if path_str.endswith("game-fixture.json"):
                    return Path(os.path.abspath(self.run_root)) / "game-fixture.json"
                if path_str.endswith("original-game-data"):
                    # Resolve this file outside the run-root to simulate a boundary escape
                    return Path(os.path.abspath(outside_file))
                if path_str.endswith("replacement-game-data"):
                    return Path(os.path.abspath(self.run_root)) / "replacement-game-data"
                if path_str.endswith("local"):
                    return Path(os.path.abspath(self.local_dir))
                return Path(os.path.abspath(path_str))
                
            mock_resolve.side_effect = fake_resolve
            
            with self.assertRaisesRegex(ValueError, "must be a regular file inside run-root"):
                validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)

    def test_valid_config_accepted(self):
        run_root, config = validate_config("converted", str(self.run_root), "SINGLE_BACKED_UP_TARGET", self.root_dir)
        self.assertEqual(run_root, self.run_root.resolve())
        self.assertEqual(config["bundleName"], self.bundle_name)
        
    def test_optimized_python_refusal(self):
        script_path = str(Path(__file__).resolve().parent.parent / "tools" / "acceptance" / "run_t08_game_recovery.py")
        p = subprocess.run([sys.executable, "-O", script_path, "--mode", "converted", "--run-root", str(self.run_root), "--allow-game-write", "SINGLE_BACKED_UP_TARGET"], capture_output=True, text=True)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("Refusing to run under optimized Python", p.stderr)

    def test_storage_restoration_failure(self):
        # We need to test the finally block in run_test.
        from unittest.mock import patch, MagicMock
        from tools.acceptance.run_t08_game_recovery import run_test
        
        args = MagicMock()
        args.mode = "move"
        args.run_root = str(self.run_root)
        args.allow_game_write = "SINGLE_BACKED_UP_TARGET"
        
        env_dir = self.root_dir / "docs" / "refactor" / "evidence"
        env_dir.mkdir(parents=True, exist_ok=True)
        (env_dir / "environment.local.json").write_text(json.dumps({
            "adbExe": "adb",
            "deviceWhitelist": [{"abi": "x86_64", "serial": "123", "androidId": "123"}]
        }), encoding="utf-8")
        
        with patch("tools.acceptance.run_t08_game_recovery.DeviceRunner") as MockRunner:
            instance = MockRunner.return_value
            instance.shell.side_effect = lambda cmd: "MANAGE_EXTERNAL_STORAGE: default" if "appops get" in cmd else ("123" if "android_id" in cmd else ("x86_64" if "ro.product.cpu.abi" in cmd else ""))
            instance.read.side_effect = lambda f: self.original_data if "target" in f or "__data" in f else b""
            instance.exists.return_value = False
            instance.target_stat.return_value = {"uid": "1", "gid": "1", "mode": "1", "size": "1", "mtime": "1", "inode": "1"}
            instance.capture.return_value = {}
            instance.run_dir = self.run_root / "fake_run_dir"
            instance.run_dir.mkdir(exist_ok=True)
            instance.target = "/fake/target"
            instance.android_id = "123"
            instance.abi = "x86_64"
            
            # The first remote mkdir -p is the only primary failure; later mkdir -p commands succeed.
            # Rollback would otherwise pass; only cmd appops set ... MANAGE_EXTERNAL_STORAGE default fails.
            mkdir_count = 0
            def fake_shell(cmd, **kwargs):
                nonlocal mkdir_count
                if "get secure android_id" in cmd:
                    return "123"
                if "ro.product.cpu.abi" in cmd:
                    return "x86_64"
                if "appops get" in cmd:
                    return "MANAGE_EXTERNAL_STORAGE: default"
                if "appops set" in cmd:
                    if "allow" in cmd:
                        return ""
                    raise Exception("Injected restoration failure")
                if "mkdir -p" in cmd:
                    mkdir_count += 1
                    if mkdir_count == 1:
                        raise RuntimeError("Injected main failure")
                    return ""
                return ""
            instance.shell.side_effect = fake_shell
            
            with self.assertRaisesRegex(RuntimeError, "Injected main failure"):
                run_test(args, self.root_dir)
                    
            result_file = instance.run_dir / "result.json"
            self.assertTrue(result_file.exists())
            report = json.loads(result_file.read_text(encoding="utf-8"))
            
            self.assertIn("Injected main failure", report["error"])
            self.assertIn("Injected restoration failure", report["storageRestorationError"])
            self.assertNotIn("Injected main failure", report["storageRestorationError"])
            self.assertEqual(report["result"], "FAIL")
            self.assertEqual(report["rollback"], "FAIL")
            self.assertNotIn("rollbackError", report)
            self.assertFalse(any("push" in call.args for call in instance.adb.call_args_list))

    def test_wrong_device_identity(self):
        from unittest.mock import patch, MagicMock
        from tools.acceptance.run_t08_game_recovery import run_test
        
        args = MagicMock()
        args.mode = "converted"
        args.run_root = str(self.run_root)
        args.allow_game_write = "SINGLE_BACKED_UP_TARGET"
        
        env_dir = self.root_dir / "docs" / "refactor" / "evidence"
        env_dir.mkdir(parents=True, exist_ok=True)
        (env_dir / "environment.local.json").write_text(json.dumps({
            "adbExe": "adb",
            "deviceWhitelist": [{"abi": "x86_64", "serial": "123", "androidId": "123"}]
        }), encoding="utf-8")
        
        with patch("subprocess.run") as mock_run:
            def fake_run(cmd, *a, **kw):
                mock_proc = MagicMock()
                mock_proc.returncode = 0
                if "android_id" in cmd:
                    mock_proc.stdout = b"wrong_id\n"
                elif "ro.product.cpu.abi" in cmd:
                    mock_proc.stdout = b"x86_64\n"
                else:
                    mock_proc.stdout = b""
                return mock_proc
            mock_run.side_effect = fake_run

            with self.assertRaisesRegex(RuntimeError, "Device android_id mismatch"):
                run_test(args, self.root_dir)
                
            self.assertEqual(len(list(self.run_root.glob("converted-*"))), 0)
            self.assertEqual((self.run_root / "original-game-data").read_bytes(), self.original_data)

    def test_duplicate_whitelist(self):
        from unittest.mock import MagicMock
        from tools.acceptance.run_t08_game_recovery import run_test
        
        args = MagicMock()
        args.mode = "converted"
        args.run_root = str(self.run_root)
        args.allow_game_write = "SINGLE_BACKED_UP_TARGET"
        
        env_dir = self.root_dir / "docs" / "refactor" / "evidence"
        env_dir.mkdir(parents=True, exist_ok=True)
        (env_dir / "environment.local.json").write_text(json.dumps({
            "adbExe": "adb",
            "deviceWhitelist": [
                {"abi": "x86_64", "serial": "123", "androidId": "123"},
                {"abi": "x86_64", "serial": "456", "androidId": "456"}
            ]
        }), encoding="utf-8")
        
        with self.assertRaisesRegex(RuntimeError, "Expected exactly one x86_64 device"):
            run_test(args, self.root_dir)
            
        self.assertEqual(len(list(self.run_root.glob("converted-*"))), 0)
        self.assertEqual((self.run_root / "original-game-data").read_bytes(), self.original_data)

    def test_main_exit_on_validation_failure(self):
        import sys
        from unittest.mock import patch
        from tools.acceptance.run_t08_game_recovery import main
        
        test_args = ["run_t08_game_recovery.py", "--mode", "converted", "--run-root", str(self.run_root), "--allow-game-write", "INVALID"]
        with patch.object(sys, "argv", test_args):
            with self.assertRaises(SystemExit) as cm:
                main()
            self.assertEqual(cm.exception.code, 1)
            self.assertEqual(len(list(self.run_root.glob("converted-*"))), 0)
