# -*- coding: utf-8 -*-
"""T55 — `tools/check_architecture.py` 的真实行为。

检查器本身必须**能抓到违规**，否则「exit 0」没有意义。这里用合成源码树逐条触发
R1–R7，再跑一次**真实仓库**确认当前是干净的。

约定（沿用 tests/test_apk_size_audit.py）：只驱动真实实现、自建输入、用完即删。
"""
import io
import json
import os
import shutil
import sys
import tempfile
import unittest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO_ROOT, "tools"))

import check_architecture as arch  # noqa: E402

SRC = os.path.join("app", "src", "main", "java", "com", "bd2toolsbox")


def write(root, rel, body):
    path = os.path.join(root, SRC, rel.replace("/", os.sep))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(body)
    return path


class CheckArchitectureTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="t55-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def rules(self, root=None):
        report = arch.check(root or self.tmp)
        return sorted({v["rule"] for v in report["violations"]}), report

    def test_clean_tree_passes(self):
        write(self.tmp, "core/model/M.kt", "package com.bd2toolsbox.core.model\nclass M\n")
        write(self.tmp, "data/repository/R.kt",
              "package com.bd2toolsbox.data.repository\nimport com.bd2toolsbox.core.model.M\nclass R\n")
        write(self.tmp, "feature/mods/F.kt",
              "package com.bd2toolsbox.feature.mods\nimport com.bd2toolsbox.data.repository.R\nclass F\n")
        write(self.tmp, "ui/screens/S.kt",
              "package com.bd2toolsbox.ui.screens\nimport androidx.compose.runtime.Composable\n"
              "@Composable fun S() {}\n")
        rules, report = self.rules()
        self.assertEqual([], rules, report["violations"])
        self.assertEqual(4, report["checkedFiles"])

    def test_r1_core_must_not_import_ui(self):
        write(self.tmp, "core/x/A.kt",
              "package com.bd2toolsbox.core.x\nimport com.bd2toolsbox.ui.screens.S\nclass A\n")
        rules, _ = self.rules()
        self.assertIn("R1", rules)

    def test_r1_core_must_not_import_feature(self):
        write(self.tmp, "core/x/A.kt",
              "package com.bd2toolsbox.core.x\nimport com.bd2toolsbox.feature.mods.F\nclass A\n")
        rules, _ = self.rules()
        self.assertIn("R1", rules)

    def test_r2_data_must_not_import_ui_or_feature(self):
        write(self.tmp, "data/y/B.kt",
              "package com.bd2toolsbox.data.y\nimport com.bd2toolsbox.feature.mods.F\nclass B\n")
        rules, _ = self.rules()
        self.assertIn("R2", rules)

    def test_r3_feature_must_not_import_ui(self):
        write(self.tmp, "feature/mods/F.kt",
              "package com.bd2toolsbox.feature.mods\nimport com.bd2toolsbox.ui.components.X\nclass F\n")
        rules, _ = self.rules()
        self.assertIn("R3", rules)

    def test_r4_pure_feature_file_must_not_import_android(self):
        write(self.tmp, "feature/mods/Pure.kt",
              "package com.bd2toolsbox.feature.mods\nimport android.util.Log\nclass Pure\n")
        rules, _ = self.rules()
        self.assertIn("R4", rules)

    def test_r4_allows_compose_and_viewmodel(self):
        write(self.tmp, "feature/mods/Row.kt",
              "package com.bd2toolsbox.feature.mods\n"
              "import androidx.compose.runtime.Composable\n@Composable fun Row() {}\n")
        write(self.tmp, "feature/mods/Vm.kt",
              "package com.bd2toolsbox.feature.mods\n"
              "import androidx.lifecycle.ViewModel\nclass Vm : ViewModel()\n")
        rules, _ = self.rules()
        self.assertNotIn("R4", rules)

    def test_r5_viewmodel_must_not_import_compose(self):
        write(self.tmp, "ui/viewmodel/V.kt",
              "package com.bd2toolsbox.ui.viewmodel\n"
              "import androidx.compose.runtime.MutableState\nclass V\n")
        rules, _ = self.rules()
        self.assertIn("R5", rules)

    def test_r6_viewmodel_must_not_inject_viewmodel(self):
        write(self.tmp, "ui/viewmodel/MainViewModel.kt",
              "package com.bd2toolsbox.ui.viewmodel\n"
              "import javax.inject.Inject\n"
              "class OtherViewModel\n"
              "class MainViewModel @Inject constructor(\n"
              "    private val other: OtherViewModel,\n"
              ") {\n}\n")
        rules, report = self.rules()
        self.assertIn("R6", rules)
        self.assertTrue(any("MainViewModel injects OtherViewModel" in v["detail"]
                            for v in report["violations"]))

    def test_r7_ui_must_not_execute_business(self):
        for imp in ("java.io.File", "java.net.URL", "okhttp3.OkHttpClient",
                    "com.chaquo.python.Python", "com.bd2toolsbox.core.engine.ModEngine",
                    "com.bd2toolsbox.core.python.PythonRuntime",
                    "com.bd2toolsbox.core.fileaccess.GameFileAccess",
                    "com.bd2toolsbox.data.repository.ModRepository"):
            with self.subTest(imp=imp):
                write(self.tmp, "ui/screens/S.kt",
                      "package com.bd2toolsbox.ui.screens\nimport %s\nclass S\n" % imp)
                rules, _ = self.rules()
                self.assertIn("R7", rules)

    def test_r7_only_applies_to_ui_screens_and_components(self):
        # ui/viewmodel 允许用仓储（它是执行入口的持有者）
        write(self.tmp, "ui/viewmodel/V.kt",
              "package com.bd2toolsbox.ui.viewmodel\n"
              "import com.bd2toolsbox.data.repository.ModRepository\nclass V\n")
        rules, _ = self.rules()
        self.assertNotIn("R7", rules)

    def test_allow_list_entries_carry_a_reason(self):
        for key, reason in arch.ALLOW.items():
            self.assertIsInstance(key, tuple)
            self.assertEqual(2, len(key))
            self.assertTrue(reason.strip(), "allow 条目必须写理由：%r" % (key,))

    def test_cli_exit_codes(self):
        clean = os.path.join(self.tmp, "clean")
        write(clean, "core/model/M.kt", "package com.bd2toolsbox.core.model\nclass M\n")
        self.assertEqual(0, arch.main(["--root", clean, "--quiet"]))
        dirty = os.path.join(self.tmp, "dirty")
        write(dirty, "core/x/A.kt",
              "package com.bd2toolsbox.core.x\nimport com.bd2toolsbox.ui.screens.S\nclass A\n")
        self.assertEqual(1, arch.main(["--root", dirty, "--quiet"]))
        self.assertEqual(2, arch.main(["--root", os.path.join(self.tmp, "nope"), "--quiet"]))

    def test_cli_writes_json(self):
        clean = os.path.join(self.tmp, "clean2")
        write(clean, "core/model/M.kt", "package com.bd2toolsbox.core.model\nclass M\n")
        out = os.path.join(self.tmp, "arch.json")
        self.assertEqual(0, arch.main(["--root", clean, "--json", out, "--quiet"]))
        with io.open(out, encoding="utf-8") as fh:
            data = json.load(fh)
        self.assertEqual(1, data["schemaVersion"])
        self.assertEqual(1, data["checkedFiles"])

    def test_real_repository_is_clean(self):
        report = arch.check(REPO_ROOT)
        self.assertGreater(report["checkedFiles"], 80)
        self.assertEqual([], report["violations"])


if __name__ == "__main__":
    unittest.main()
