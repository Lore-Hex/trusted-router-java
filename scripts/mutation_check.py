#!/usr/bin/env python3
"""Run recorded boundary mutants in an isolated copy; Python 3 stdlib only."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def run(work, tests, log):
    wrapper = 'gradlew.bat' if os.name == 'nt' else './gradlew'
    command = [wrapper, 'test', '--offline', '--console=plain', '-PmutationRuntime',
               '-x', 'boundaryCheck', '-x', 'jacocoTestReport']
    for test in sorted(set(tests)):
        command.extend(['--tests', test])
    # No old XML may count as a killed mutation.
    shutil.rmtree(work / 'build/test-results/test', ignore_errors=True)
    with log.open('w') as output:
        return subprocess.run(command, cwd=work, stdout=output, stderr=subprocess.STDOUT,
                              timeout=240).returncode


def failed_test(work, test):
    class_name, method = test.rsplit('.', 1)
    report = work / 'build/test-results/test' / ('TEST-' + class_name + '.xml')
    if not report.exists():
        return False
    tree = ET.parse(report)
    return any(case.get('name') in (method, method + '()') and case.find('failure') is not None
               for case in tree.findall('.//testcase'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only', help='Run one mutation id (baseline still required)')
    args = parser.parse_args()
    mutations = json.loads((ROOT / 'scripts/mutations.json').read_text())
    if args.only:
        mutations = [item for item in mutations if item['id'] == args.only]
        if not mutations:
            parser.error('unknown mutation id')
    start = time.monotonic()
    output = ROOT / 'build/mutation-results'
    output.mkdir(parents=True, exist_ok=True)
    results = []
    success = True
    with tempfile.TemporaryDirectory(prefix='tr-java-mutations-') as temporary:
        work = Path(temporary) / 'java'
        shutil.copytree(ROOT, work, ignore=shutil.ignore_patterns(
            '.git', '.gradle', 'build', '__pycache__', '.idea'))
        # Check all patterns before spending time on Gradle; never silently skip stale records.
        for item in mutations:
            source = (work / item['file']).read_bytes()
            count = source.count(item['before'].encode())
            if count != 1:
                print(f"STALE {item['id']}: expected exactly one before pattern, found {count}", flush=True)
                return 1
        baseline = run(work, [item['test'] for item in mutations], output / 'baseline.log')
        if baseline:
            print(f'ERROR: baseline failed; see {output / "baseline.log"}', flush=True)
            return 1
        for item in mutations:
            path = work / item['file']
            original = path.read_bytes()
            began = time.monotonic()
            status = 'ERROR'
            try:
                before = item['before'].encode()
                if original.count(before) != 1:
                    raise ValueError(f"stale mutation {item['id']}")
                path.write_bytes(original.replace(before, item['after'].encode(), 1))
                code = run(work, [item['test']], output / (item['id'] + '.log'))
                status = 'KILLED' if code and failed_test(work, item['test']) else ('SURVIVED' if code == 0 else 'ERROR')
            finally:
                # Restore exact bytes from memory even on timeout, error or Ctrl-C.
                path.write_bytes(original)
                if path.read_bytes() != original:
                    raise RuntimeError(f'failed to restore {path}')
            elapsed = time.monotonic() - began
            results.append({'id': item['id'], 'test': item['test'], 'status': status, 'seconds': round(elapsed, 3)})
            print(f'{status} {item["id"]}: {elapsed:.2f}s', flush=True)
            success &= status == 'KILLED'
    elapsed = time.monotonic() - start
    (output / 'results.json').write_text(json.dumps({'seconds': round(elapsed, 3), 'results': results}, indent=2) + '\n')
    print(f'Mutation wall time: {elapsed:.2f}s; {sum(r["status"] == "KILLED" for r in results)}/{len(results)} killed.', flush=True)
    if elapsed > 300:
        print('Wall time exceeded five minutes; see audit notes before expanding the mutation set.', flush=True)
    return 0 if success else 1


if __name__ == '__main__':
    sys.exit(main())
