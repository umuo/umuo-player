#!/usr/bin/env python3
"""导出可公开的计数与合成用例结果，不复制主机名、凭据、绝对路径或原始日志。"""
import json
from datetime import datetime,timezone
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
reports = []
for path in sorted((root / 'shared/build/test-results/jvmTest').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    reports.append({key: suite.attrib[key] for key in ['name', 'tests', 'failures', 'errors', 'skipped']})
android_reports = []
for path in sorted((root / 'playback-media3/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    android_reports.append({key: suite.attrib[key] for key in ['name', 'tests', 'failures', 'errors', 'skipped']})
checks = []
formats = []
for path in sorted((root / 'verification/local').glob('*.json')):
    result = json.loads(path.read_text())
    if isinstance(result,list):
        if path.name=='format-matrix.json':formats=result
        continue
    if result.get('result') == 'passed':
        checks.append({key: result[key] for key in ['source', 'ui', 'result', 'server']})
metrics = []
for path in sorted((root / 'verification/local').glob('*-metrics.log')):
    log = path.read_text()
    metrics.append({'case': path.stem, 'first_frame_ms': [int(v) for v in re.findall(r'first_frame_ms=(\d+)', log)],
                    'seek_ready_ms': [int(v) for v in re.findall(r'seek_ready_ms=(\d+)', log)]})
lint = {}
for module in ['app-mobile', 'app-tv']:
    path = root / module / 'build/reports/lint-results-debug.xml'
    if path.exists():
        issues = ET.parse(path).getroot().findall('issue')
        lint[module] = {s: sum(i.get('severity') == s for i in issues) for s in ['Error', 'Fatal', 'Warning']}
result = {'date_utc': datetime.now(timezone.utc).date().isoformat(), 'environment': 'synthetic fixtures; Android emulator; see docs/VERIFICATION.md for limitations',
          'jvm_suites': reports, 'android_suites': android_reports, 'ui_cases': checks, 'format_matrix': formats, 'metrics': metrics, 'lint': lint}
(root / 'verification/summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print('Wrote verification/summary.json')
