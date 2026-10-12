# -*- coding: utf-8 -*-
"""T59：转换耗时量测脚本本身的回归测试。

量测脚本也会坏 —— 坏掉的方式还很隐蔽（报一个好看的数字）。这里断言的是
**输入规模与结果自洽**，以及脚本对干扰项的处理正确（`.old` 备份目录与
`.nomedia` 标记必须被跳过），不是具体毫秒数（那随机器变）。
"""
import importlib.util
import io
import json
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _load():
    path = os.path.join(ROOT, 'tools', 'measure_conversion.py')
    spec = importlib.util.spec_from_file_location('measure_conversion', path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_build_tree_shape_matches_parameters():
    mod = _load()
    with tempfile.TemporaryDirectory() as tmp:
        total = mod.build_tree(tmp, bundles=4, files_per_bundle=3)
        # 每个 bundle：3 个目录各 3 个 dat = 9，加 skel+json = 11
        assert total == 4 * 11, total
        for b in range(4):
            assert os.path.isdir(os.path.join(tmp, f'bundle{b:03d}', 'atlas'))
            assert os.path.isfile(os.path.join(tmp, f'bundle{b:03d}', '.nomedia'))
            assert os.path.isfile(os.path.join(tmp, f'bundle{b:03d}', '.old', 'stale.dat'))


def test_index_skips_backup_and_marker_files():
    """索引必须跳过 `.old` 与 `.nomedia` —— 否则耗时数字会被备份目录撑大。"""
    mod = _load()
    sys.path.insert(0, os.path.join(ROOT, 'app', 'src', 'main', 'python'))
    from repacker import repacker

    with tempfile.TemporaryDirectory() as tmp:
        mod.build_tree(tmp, bundles=3, files_per_bundle=2)
        index = repacker._build_file_index(tmp)
        assert len(index['skel_json']) == 3, index['skel_json'].keys()
        assert not any('.old' in p for p in index['all_files'])
        assert not any(p.endswith('.nomedia') for p in index['all_files'])


def test_main_reports_self_consistent_numbers():
    """端到端跑一次（小规模），断言报告里的规模与中位数自洽。"""
    mod = _load()
    out = os.path.join(tempfile.mkdtemp(prefix='t59-report-'), 'r.json')
    argv = sys.argv
    sys.argv = ['measure_conversion.py', '--repeat', '2', '--bundles', '3',
                '--files-per-bundle', '2', '--bones', '4', '--slots', '3',
                '--animations', '1', '--json', out]
    try:
        rc = mod.main()
    finally:
        sys.argv = argv
    assert rc == 0
    report = json.load(io.open(out, encoding='utf-8'))
    assert report['repeat'] == 2
    assert report['input']['bundles'] == 3
    assert len(report['buildFileIndexMs']) == 2
    assert len(report['jsonToSkelMs']) == 2
    assert report['outputSkelBytes'] > 0
    # 中位数必须落在原始样本区间内 —— 不然报告就是自己编的
    for raw_key, median_key in (('buildFileIndexMs', 'buildFileIndexMedianMs'),
                                ('jsonToSkelMs', 'jsonToSkelMedianMs')):
        lo, hi = min(report[raw_key]), max(report[raw_key])
        assert lo - 1e-6 <= report[median_key] <= hi + 1e-6, (raw_key, report)


def test_main_rejects_unsupported_spine_version():
    """脚本的 skel 源必须是转换器支持的那一版；换了版本要立刻炸，不能静默出 0 字节。"""
    mod = _load()
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, 'bad.json')
        mod.make_skel_json(src, 2, 2, 1)
        doc = json.load(io.open(src, encoding='utf-8'))
        doc['skeleton']['spine'] = '3.8.75'
        with io.open(src, 'w', encoding='utf-8', newline='\n') as fh:
            json.dump(doc, fh)
        sys.path.insert(0, os.path.join(ROOT, 'app', 'src', 'main', 'python'))
        from repacker import json_to_skel as j2s
        try:
            j2s.json_to_skel(src, os.path.join(tmp, 'out.skel'))
        except Exception as exc:
            assert 'unsupported Spine version' in str(exc), exc
        else:
            raise AssertionError('不支持的 Spine 版本必须报错，不能产出文件')
