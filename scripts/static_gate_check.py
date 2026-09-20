#!/usr/bin/env python3
"""Prove static gate rejection using isolated, deliberately defective sources."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
BOUNDARY_PROBES = {
    'DecodedCast': 'Object f(Object o) { return (java.util.Map<String, Object>) o; }',
    'JsonExtraction': 'String f(com.google.gson.JsonObject o) { return o.get("x").getAsString(); }',
    'InputLaundering': 'String f(java.util.Map<String, Object> o) { return String.valueOf(o.get("key")); }',
    'HeaderMapOperation': 'String f(java.util.Map<String, String> headers) { return headers.get("Authorization"); }',
    'BroadCatch': 'Object f() { try { return Integer.valueOf("x"); } catch (RuntimeException e) { return null; } }',
    'OptionalGet': 'String f(java.util.Optional<String> value) { return value.get(); }',
    'WireSwitch': 'int f(String value) { switch (value) { case "ok": return 1; } return 0; }',
}
ERROR_PRONE_PROBES = {
    'UnusedVariable': 'int f() { int forgotten = 1; return 0; }',
    'MissingCasesInEnumSwitch': 'enum Value { A, B } int f(Value value) { switch (value) { case A: return 1; } return 0; }',
    'ReturnValueIgnored': 'void f(String value) { value.trim(); }',
    'FutureReturnValueIgnored': 'void f(java.util.concurrent.ExecutorService executor) { executor.submit(() -> {}); }',
    'CatchAndPrintStackTrace': 'void f(java.io.InputStream input) { try { input.read(); } catch (java.io.IOException e) { e.printStackTrace(); } }',
    'EmptyCatch': 'void f(java.io.InputStream input) { try { input.read(); } catch (java.io.IOException e) {} }',
    'ClassCanBeStatic': 'private final class Inner { int value() { return 1; } } int f() { return new Inner().value(); }',
}


def main():
    began = time.monotonic()
    output = ROOT / 'build/static-gate-results'
    output.mkdir(parents=True, exist_ok=True)
    results = []
    with tempfile.TemporaryDirectory(prefix='tr-java-static-') as temporary:
        work = Path(temporary) / 'java'
        shutil.copytree(ROOT, work, ignore=shutil.ignore_patterns('.git', '.gradle', 'build', '__pycache__'))
        probe = work / 'src/main/java/com/trustedrouter/StaticGateProbe.java'
        wrapper = 'gradlew.bat' if os.name == 'nt' else './gradlew'
        def compile_probe():
            return subprocess.run([wrapper, 'compileJava', '--offline', '--console=plain', '-x', 'boundaryCheck'],
                                  cwd=work, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=180)
        baseline = compile_probe()
        if baseline.returncode:
            (output / 'baseline.log').write_text(baseline.stdout)
            print('Static baseline failed; see build/static-gate-results/baseline.log')
            return 1
        for rule, body in {**BOUNDARY_PROBES, **ERROR_PRONE_PROBES}.items():
            try:
                probe.write_text('package com.trustedrouter;\nfinal class StaticGateProbe {\n' + body + '\n}\n')
                if rule in BOUNDARY_PROBES:
                    result = subprocess.run([sys.executable, 'scripts/boundary_check.py'], cwd=work,
                                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=30)
                else:
                    result = compile_probe()
                killed = result.returncode != 0 and '[' + rule + ']' in result.stdout
                (output / (rule + '.log')).write_text(result.stdout)
                results.append({'rule': rule, 'rejected': killed})
                print(f'{"REJECTED" if killed else "ERROR"} {rule}', flush=True)
            finally:
                probe.unlink(missing_ok=True)
    seconds = round(time.monotonic() - began, 3)
    (output / 'results.json').write_text(json.dumps({'seconds': seconds, 'results': results}, indent=2) + '\n')
    print(f'Static proof wall time: {seconds}s', flush=True)
    return 0 if all(result['rejected'] for result in results) else 1


if __name__ == '__main__':
    sys.exit(main())
