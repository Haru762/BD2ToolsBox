"""Pinned test inputs must survive Git's Windows checkout filters unchanged."""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FixtureCheckoutTest(unittest.TestCase):
    def test_checkout_preserves_manifest_hashes_with_and_without_autocrlf(self):
        manifest = json.loads((ROOT / "docs/refactor/fixture-manifest.json").read_text(encoding="utf8"))
        fixtures = [entry for entry in manifest["derivedTargets"]
                    if entry.get("storageClass") == "repo-resource"]
        self.assertTrue(fixtures, "The manifest must select real repository fixtures")
        for fixture in fixtures:
            paths = [fixture["targetPath"]]
            if fixture.get("companionPath"):
                paths.append(fixture["companionPath"])
            for path in paths:
                for autocrlf in ("true", "false"):
                    with self.subTest(path=path, autocrlf=autocrlf):
                        checkout = subprocess.run(
                            ["git", "-C", str(ROOT), "-c", f"core.autocrlf={autocrlf}",
                             "cat-file", "--filters", f"--path={path}", f"HEAD:{path}"],
                            capture_output=True, check=True, timeout=15,
                        ).stdout
                        self.assertEqual(fixture["sha256"], hashlib.sha256(checkout).hexdigest())
                        self.assertEqual(fixture["bytes"], len(checkout))


if __name__ == "__main__":
    unittest.main()
