"""Explicitly authorized single-target T08 device acceptance driver."""
import argparse
import datetime
import hashlib
import json
import re
import subprocess
import time
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

APP = "com.bd2toolsbox"
GAME = "com.neowizgames.game.browndust2"
REMOTE_FILES = "/data/user/0/com.bd2toolsbox/"
PROTECTED = ["files/" + x for x in ("installed_mods.json", "install_journal.json", "mod_list_snapshot.json", "mod_cache.json", "local_bundle_index.json", "characters.json")] + ["shared_prefs/app_settings.xml"]

def _sha(data):
    return hashlib.sha256(data).hexdigest()

class DeviceRunner:
    def __init__(self, adb_exe, serial, android_id, abi):
        self.adb_exe = adb_exe
        self.serial = serial
        self.android_id = android_id
        self.abi = abi
        self.adb_cmd = [self.adb_exe, "-s", self.serial]
        self.run_id = None
        self.run_dir = None
        self.target = None

    def adb(self, *argv, check=True):
        p = subprocess.run(self.adb_cmd + list(argv), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if check and p.returncode:
            raise RuntimeError(f"adb failed {argv}: {p.stderr.decode('utf-8', 'replace')}")
        return p

    def shell(self, command, check=True):
        return self.adb("shell", command, check=check).stdout.decode("utf-8", "replace").strip()

    def read(self, remote):
        return self.adb("exec-out", "cat " + remote).stdout

    def exists(self, remote):
        p = self.adb("shell", "test -e " + remote, check=False)
        if p.returncode not in (0, 1):
            raise RuntimeError("Cannot determine remote existence: " + remote)
        return p.returncode == 0

    def target_stat(self):
        fields = self.shell("stat -c '%u %g %a %s %Y %i' " + self.target).split()
        return dict(zip(("uid", "gid", "mode", "size", "mtime", "inode"), fields))

    def capture(self, label):
        info = {}
        for relative in PROTECTED:
            remote = REMOTE_FILES + relative
            if not self.exists(remote):
                info[relative] = {"exists": False}
                continue
            body = self.read(remote)
            dest = self.run_dir / label / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(body)
            info[relative] = {"exists": True, "sha256": _sha(body), "stat": self.shell("stat -c '%u %g %a %Y' " + remote).split()}
        (self.run_dir / (label + ".json")).write_text(json.dumps(info, indent=2), encoding="utf-8")
        return info

    def restore_file(self, local, remote, metadata, nonce):
        tmp = "/data/local/tmp/t08-" + self.run_id
        upload = tmp + "/restore-" + nonce
        self.adb("push", str(local), upload)
        uid, gid, mode, mtime = metadata
        assert all(re.fullmatch(r"[0-9]+", x) for x in metadata)
        temporary = remote + ".t08-restore-" + self.run_id
        assert not self.exists(temporary)
        self.shell(f"cp {upload} {temporary} && chown {uid}:{gid} {temporary} && chmod {mode} {temporary} && touch -m -d @{mtime} {temporary} && mv {temporary} {remote}")
        assert _sha(self.read(remote)) == _sha(local.read_bytes())

    def instrument(self, method):
        p = self.adb("shell", "am", "instrument", "-w", "-r", "-e", "class", "com.bd2toolsbox.service.RealGameRecoveryDeviceTest#" + method,
                "-e", "allowGameWrite", "single-target-backed-up", "-e", "runId", self.run_id,
                "com.bd2toolsbox.test/androidx.test.runner.AndroidJUnitRunner", check=False)
        text = p.stdout.decode("utf-8", "replace")
        (self.run_dir / (method + ".txt")).write_bytes(p.stdout + p.stderr)
        codes = [int(x) for x in re.findall(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", text)]
        assert p.returncode == 0 and codes.count(1) == codes.count(0) == 1 and not any(x < 0 for x in codes), text
        return text

    def ui(self, label):
        tmp = "/data/local/tmp/t08-" + self.run_id
        self.shell("uiautomator dump " + tmp + "/ui.xml")
        text = self.read(tmp + "/ui.xml")
        (self.run_dir / (label + ".xml")).write_bytes(text)
        image = self.adb("exec-out", "screencap -p").stdout
        (self.run_dir / (label + ".png")).write_bytes(image)
        return ET.fromstring(text)

    def tap_text(self, tree, text):
        nodes = [n for n in tree.iter("node") if n.get("text") == text]
        if len(nodes) != 1:
            raise RuntimeError("Expected one UI control: " + text)
        box = [int(x) for x in re.findall(r"\d+", nodes[0].get("bounds", ""))]
        assert len(box) == 4
        self.shell(f"input tap {(box[0] + box[2]) // 2} {(box[1] + box[3]) // 2}")


def validate_config(args_mode, args_run_root, args_allow_game_write, root_dir):
    if args_mode not in ("converted", "move"):
        raise ValueError("invalid args_mode")

    if args_allow_game_write != "SINGLE_BACKED_UP_TARGET":
        raise ValueError("Missing explicit allow game write flag.")
    
    run_root = Path(args_run_root).resolve()
    local_dir = (root_dir / "local").resolve()
    if not run_root.is_relative_to(local_dir) or run_root == local_dir:
        raise ValueError("run-root must be within local directory.")

    game_fixture = (run_root / "game-fixture.json").resolve()
    original_data = (run_root / "original-game-data").resolve()
    replacement_data = (run_root / "replacement-game-data").resolve()
    
    for path in (game_fixture, original_data, replacement_data):
        if not path.is_file() or not path.is_relative_to(run_root):
            raise ValueError(f"File {path.name} must be a regular file inside run-root")

    with game_fixture.open("r", encoding="utf-8") as f:
        config = json.load(f)

    if not re.fullmatch(r"[a-f0-9]{32}", config.get("bundleName", "")):
        raise ValueError("malformed bundleName")
    if not re.fullmatch(r"[a-f0-9]{32}", config.get("hashDir", "")):
        raise ValueError("malformed hashDir")
    
    if "expectedAppAndroidId" not in config or not re.fullmatch(r"[a-f0-9]{16}", config["expectedAppAndroidId"]):
        raise ValueError("expectedAppAndroidId missing or malformed")

    if config.get("oldSha256") == config.get("newSha256"):
        raise ValueError("replacement equals original")
    
    if _sha(original_data.read_bytes()) != config.get("oldSha256"):
        raise ValueError("original-game-data SHA mismatch")
    
    if _sha(replacement_data.read_bytes()) != config.get("newSha256"):
        raise ValueError("replacement-game-data SHA mismatch")

    remote = config.get("remote")
    expected_remote = f"/storage/emulated/0/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/{config.get('bundleName', '')}/{config.get('hashDir', '')}/__data"
    if remote != expected_remote:
        raise ValueError("malformed remote target")
    
    if "mode" in config and config["mode"] not in ("converted", "move"):
        raise ValueError("malformed mode in config")

    for key, data_path in (("oldBytes", original_data), ("newBytes", replacement_data)):
        if key not in config:
            raise ValueError(f"{key} is missing")
        val = config[key]
        if isinstance(val, bool) or not isinstance(val, int) or val <= 0:
            raise ValueError(f"{key} must be a positive int")
        if val != len(data_path.read_bytes()):
            raise ValueError(f"{key} must equal the length of the data")

    return run_root, config

def run_test(args, root_dir):
    if not __debug__:
        raise RuntimeError("Refusing to run under optimized Python (-O) due to stripped safety asserts.")

    run_root, config = validate_config(args.mode, args.run_root, args.allow_game_write, root_dir)

    env_json = root_dir / "docs" / "refactor" / "evidence" / "environment.local.json"
    with env_json.open("r", encoding="utf-8") as f:
        env = json.load(f)
    
    adb_exe = env.get("adbExe")
    if not adb_exe:
        raise RuntimeError("adbExe is missing from environment.local.json")

    whitelist = env.get("deviceWhitelist", [])
    devices = [d for d in whitelist if d.get("abi") == "x86_64"]
    if len(devices) != 1:
        raise RuntimeError(f"Expected exactly one x86_64 device in whitelist, found {len(devices)}")
    device = devices[0]
    
    if not device.get("serial") or not device.get("androidId"):
         raise RuntimeError("Device missing serial or androidId in whitelist")

    runner = DeviceRunner(adb_exe, device["serial"], device["androidId"], device["abi"])

    actual_android_id = runner.shell("settings get secure android_id")
    if actual_android_id != runner.android_id:
        raise RuntimeError(f"Device android_id mismatch. Expected {runner.android_id}, got {actual_android_id}")
    actual_abi = runner.shell("getprop ro.product.cpu.abi")
    if actual_abi != runner.abi:
        raise RuntimeError(f"Device abi mismatch. Expected {runner.abi}, got {actual_abi}")

    run_id = args.mode + "-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    runner.run_id = run_id
    runner.run_dir = run_root / run_id
    runner.run_dir.mkdir()
    
    remote_base = "/storage/emulated/0/Android/data/com.bd2toolsbox/cache/t08-real-" + run_id
    tmp_base = "/data/local/tmp/t08-" + run_id

    config.update(runId=run_id, mode=args.mode, installedAt=int(time.time() * 1000))
    runner.target = "/storage/emulated/0/Android/data/" + GAME + "/files/UnityCache/Shared/" + config["bundleName"] + "/" + config["hashDir"] + "/__data"
    assert runner.target == config["remote"]
    rollback_target = runner.target + ".t08-original-" + run_id

    report = {"runId": run_id, "mode": args.mode, "result": "FAIL", "gameTarget": runner.target}
    backup = None
    process = None
    
    try:
        assert not runner.shell("pidof " + GAME + " || true"), "Game must already be stopped"
        runner.shell("am force-stop " + APP)
        assert _sha(runner.read(runner.target)) == config["oldSha256"]
        assert not runner.exists(remote_base)
        assert not runner.exists(REMOTE_FILES + "files/install_journal.json"), "Preserve preexisting operation journal"
        
        if args.mode == "move":
            assert not runner.exists("/storage/emulated/0/Download/Shared"), "Never mix test move with existing Download/Shared"
        
        backup = runner.capture("before")
        if args.mode == "move":
            setting = runner.shell("cmd appops get " + APP + " MANAGE_EXTERNAL_STORAGE")
            mode_match = re.search(r"MANAGE_EXTERNAL_STORAGE: (allow|ignore|deny|default)", setting)
            report["storageAccessBefore"] = mode_match.group(1) if mode_match else "default"
            runner.shell("cmd appops set " + APP + " MANAGE_EXTERNAL_STORAGE allow")
            
        report["targetBefore"] = runner.target_stat()
        runner.shell("mkdir -p " + remote_base + " " + tmp_base)

        assert not runner.exists(rollback_target)
        runner.adb("push", str(run_root / "original-game-data"), tmp_base + "/original")
        meta = report["targetBefore"]
        assert all(re.fullmatch(r"[0-9]+", meta[k]) for k in ("uid", "gid", "mode", "mtime"))
        runner.shell(f"cp {tmp_base}/original {rollback_target} && chown {meta['uid']}:{meta['gid']} {rollback_target} && chmod {meta['mode']} {rollback_target} && touch -m -d @{meta['mtime']} {rollback_target}")
        assert _sha(runner.read(rollback_target)) == config["oldSha256"]
        assert runner.shell("stat -c '%u %g %a %s %Y' " + rollback_target).split() == [meta[k] for k in ("uid", "gid", "mode", "size", "mtime")]
        report["rollbackPreparedBeforeWrite"] = True
        
        (runner.run_dir / "config.json").write_text(json.dumps(config, indent=2), encoding="utf-8")
        runner.adb("push", str(runner.run_dir / "config.json"), remote_base + "/config.json")
        source = remote_base + "/source/" + config["bundleName"] + "/" + config["hashDir"]
        if args.mode == "move":
            source = "/storage/emulated/0/Download/Shared/" + config["bundleName"] + "/" + config["hashDir"]
        runner.shell("mkdir -p " + source)
        runner.adb("push", str(run_root / "replacement-game-data"), source + "/__data")
        
        with (runner.run_dir / "crash-instrumentation.txt").open("wb") as log:
            process = subprocess.Popen(runner.adb_cmd + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                "com.bd2toolsbox.service.RealGameRecoveryDeviceTest#replaceOneTargetAndWaitForHostProcessDeath",
                "-e", "allowGameWrite", "single-target-backed-up", "-e", "runId", run_id,
                "com.bd2toolsbox.test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 100
            ready = None
            while time.monotonic() < deadline:
                if runner.exists(remote_base + "/ready.json"):
                    try:
                        ready = json.loads(runner.read(remote_base + "/ready.json"))
                        break
                    except json.JSONDecodeError:
                        pass
                if process.poll() is not None:
                    raise RuntimeError("Instrumentation ended before crash marker; see raw log")
                time.sleep(0.25)
            assert ready is not None, "Crash marker not reached"
            (runner.run_dir / "ready.json").write_text(json.dumps(ready, indent=2), encoding="utf-8")
            assert ready["runId"] == run_id
            assert _sha(runner.read(runner.target)) == config["newSha256"]
            assert _sha(runner.read(REMOTE_FILES + "files/installed_mods.json")) == ready["ledgerBeforeSha256"]
            assert _sha(runner.read(REMOTE_FILES + "files/install_journal.json")) == ready["journalSha256"]
            report["targetAfterReplace"] = runner.target_stat()
            runner.capture("at-crash")
            runner.shell("am force-stop " + APP)
            process.wait(timeout=30)
            assert not runner.shell("pidof " + APP + " || true"), "Old target PID survived force-stop"
            report["killedPid"] = ready["pid"]
            
        runner.instrument("freshProcessRejectsDuplicateSubmission")
        runner.shell("am force-stop " + APP)
        launch = runner.shell("am start -W -n com.bd2toolsbox/.MainActivity")
        (runner.run_dir / "launch.txt").write_text(launch, encoding="utf-8")
        assert "Status: ok" in launch and "COLD" in launch
        report["recoveryPid"] = int(runner.shell("pidof " + APP))
        assert report["recoveryPid"] != ready["pid"]
        
        deadline = time.monotonic() + 40
        content = ""
        while True:
            tree = runner.ui("pending-ui")
            content = " ".join(n.get("text", "") for n in tree.iter("node"))
            if "核对并修复记录" in content:
                break
            assert time.monotonic() < deadline, "Real startup recovery notice did not appear"
            time.sleep(1)
        assert "核对" in content
        
        deadline = time.monotonic() + 60
        interactions = []
        report["recoveryUiActions"] = interactions
        attempt = 0
        while runner.exists(REMOTE_FILES + "files/install_journal.json"):
            assert time.monotonic() < deadline, "Recovery did not close journal"
            attempt += 1
            tree = runner.ui("recovery-step-" + str(attempt))
            texts = [n.get("text", "") for n in tree.iter("node")]
            if "跳过" in texts and any("建立文件索引" in t for t in texts):
                runner.tap_text(tree, "跳过")
                interactions.append("dismiss-index-confirmation")
            elif "核对并修复记录" in texts:
                if any("上次装入可能没有完整收尾" in t for t in texts):
                    runner.tap_text(tree, "核对并修复记录")
                    interactions.append("request-content-reconciliation")
                else:
                    raise RuntimeError("Recovery returned a notice instead of completing: " + " | ".join(texts))
            time.sleep(0.5)
            
        runner.ui("recovered-ui")
        ledger_body = runner.read(REMOTE_FILES + "files/installed_mods.json")
        ledger = json.loads(ledger_body)["records"]
        uri = "content://com.bd2toolsbox.e2e/" + run_id
        expected = {"modUri": uri, "modName": "T08 crash acceptance " + args.mode, "familyKey": config["bundleName"],
                    "snapshotTargetHash": config["bundleName"], "quality": "HD", "usedAstc": False, "installedAt": config["installedAt"]}
        assert ledger[uri] == expected
        assert ledger["content://com.bd2toolsbox.e2e/unrelated/" + run_id]["modName"] == "Preserved unrelated acceptance record"
        assert _sha(runner.read(runner.target)) == config["newSha256"]
        assert runner.target_stat() == report["targetAfterReplace"], "Recovery rewrote the game file"
        runner.capture("after-recovery")
        runner.shell("am force-stop " + APP)
        runner.instrument("recoveredLedgerHasFrozenRecordAndUnrelatedRecord")
        runner.shell("am force-stop " + APP)
        runner.shell("am start -W -n com.bd2toolsbox/.MainActivity")
        time.sleep(3)
        tree = runner.ui("second-start-ui")
        assert "核对并修复记录" not in " ".join(n.get("text", "") for n in tree.iter("node"))
        assert _sha(runner.read(REMOTE_FILES + "files/installed_mods.json")) == _sha(ledger_body)
        assert _sha(runner.read(runner.target)) == config["newSha256"] and runner.target_stat() == report["targetAfterReplace"]
        report["result"] = "PASS"
        
    except Exception as exc:
        report["error"] = repr(exc)
        try:
            (runner.run_dir / "failure-logcat.txt").write_bytes(runner.adb("logcat", "-d", "-t", "1500", check=False).stdout)
        except Exception:
            pass
        raise
    finally:
        if backup is not None:
            try:
                runner.shell("am force-stop " + APP)
                if process is not None and process.poll() is None:
                    process.wait(timeout=30)
                assert not runner.shell("pidof " + GAME + " || true"), "Game launched during test; manual recovery required"
                current_sha = _sha(runner.read(runner.target))
                assert current_sha in (config["oldSha256"], config["newSha256"]), "Unexpected external game change; do not overwrite it"
                if current_sha != config["oldSha256"]:
                    (runner.run_dir / "game-before-rollback").write_bytes(runner.read(runner.target))
                    assert report.get("rollbackPreparedBeforeWrite") and _sha(runner.read(rollback_target)) == config["oldSha256"]
                    runner.shell("mv " + rollback_target + " " + runner.target)
                elif runner.exists(rollback_target):
                    runner.shell("mv " + rollback_target + " " + remote_base + "/unused-original-rollback")
                
                archive = REMOTE_FILES + "files/.t08-acceptance-archive-" + run_id
                runner.shell("mkdir -p " + archive)
                for index, (relative, entry) in enumerate(backup.items()):
                    remote = REMOTE_FILES + relative
                    if entry["exists"]:
                        if not runner.exists(remote) or _sha(runner.read(remote)) != entry["sha256"]:
                            runner.restore_file(runner.run_dir / "before" / relative, remote, entry["stat"], "app" + str(index))
                    elif runner.exists(remote):
                        runner.shell("mv " + remote + " " + archive + "/" + relative.rsplit("/", 1)[-1])
                        
                if args.mode == "move" and runner.exists("/storage/emulated/0/Download/Shared"):
                    runner.shell("mv /storage/emulated/0/Download/Shared /storage/emulated/0/Download/.t08-retained-" + run_id)
                    
                after = runner.capture("after-rollback")
                assert all(after[k]["exists"] == v["exists"] and (not v["exists"] or after[k]["sha256"] == v["sha256"]) for k, v in backup.items())
                restored = runner.target_stat()
                assert _sha(runner.read(runner.target)) == config["oldSha256"]
                assert all(restored[k] == report["targetBefore"][k] for k in ("uid", "gid", "mode", "size", "mtime"))
                if "error" not in report:
                    report["rollback"] = "PASS"
                else:
                    report["rollback"] = "PASS_AFTER_ERROR"  # Or something, but instructions say result and rollback are FAIL when restoration fails. Wait, if there was a primary error, rollback might pass, but report["rollback"] is not specified.
                report["targetRestored"] = restored
            except Exception as exc:
                report["rollback"] = "FAIL"
                report["rollbackError"] = repr(exc)
                if "error" not in report:
                    report["result"] = "FAIL"
                    report["error"] = report["rollbackError"]
                # DO NOT RAISE HERE! That would swallow the primary exception.
            finally:
                try:
                    if "storageAccessBefore" in report:
                        try:
                            runner.shell("cmd appops set " + APP + " MANAGE_EXTERNAL_STORAGE " + report["storageAccessBefore"])
                            report["storageAccessRestored"] = True
                        except Exception as exc:
                            report["storageAccessRestored"] = False
                            report["storageRestorationError"] = repr(exc)
                            report["result"] = "FAIL"
                            report["rollback"] = "FAIL"
                finally:
                    (runner.run_dir / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        else:
            if runner.run_dir and runner.run_dir.exists():
                (runner.run_dir / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    
    print(json.dumps(report, indent=2))
    return report

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("converted", "move"), required=True)
    parser.add_argument("--run-root", required=True)
    parser.add_argument("--allow-game-write", required=True)
    args = parser.parse_args()
    
    root_dir = Path(__file__).resolve().parent.parent.parent
    try:
        report = run_test(args, root_dir)
        if report.get("result") != "PASS":
            sys.exit(1)
    except Exception as e:
        print(f"{e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()

