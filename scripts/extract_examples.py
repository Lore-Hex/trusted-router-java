#!/usr/bin/env python3
"""Extract every README/docs code fence; compile fragments with explicit surrounding context."""
from pathlib import Path
import re
import shutil
import os
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/generated/examples'
IMPORTS = '\n'.join('import ' + package + '.*;' for package in (
    'com.trustedrouter', 'com.trustedrouter.models', 'com.trustedrouter.requests',
    'com.trustedrouter.oauth', 'com.trustedrouter.streaming', 'com.trustedrouter.errors',
    'com.trustedrouter.attestation', 'com.trustedrouter.receipts', 'com.google.gson', 'java.util'))
# Fragments refer to values supplied by the surrounding application. These are typed
# real SDK values, not stub SDK classes. Local declarations shadow these fields.
CONTEXT = '''
    TrustedRouterClient client;
    TrustedRouterClient publicClient;
    ChatRequest request;
    ChatCompletion completion;
    OAuthAuthorization authorization;
    java.net.URI callbackUri;
    JsonObject advisorParameters, synthParameters, selectorParameters, mapReduceParameters;
    byte[] requestBytes, responseBytes, attestationBytes;
    String nonce, compactJws;
'''


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    (OUT / 'java').mkdir(parents=True)
    (OUT / 'kotlin').mkdir()
    inventory = []
    for path in [ROOT / 'README.md', *sorted((ROOT / 'docs').rglob('*.md'))]:
        source = path.read_text(encoding='utf-8')
        fences = list(re.finditer(r'^```([^\n]*)\n(.*?)^```\s*$', source, re.M | re.S))
        if len(re.findall(r'^```', source, re.M)) != 2 * len(fences):
            raise AssertionError(f'unclosed code fence: {path}')
        for match in fences:
            language, code = match.group(1).strip(), match.group(2)
            origin = f'{path.relative_to(ROOT)}:{source.count(chr(10), 0, match.start()) + 1}'
            name = f'DocExample{len(inventory) + 1}'
            if language == 'java':
                imports = '\n'.join(re.findall(r'^import .*;', code, re.M))
                body = re.sub(r'^import .*;\n', '', code, flags=re.M)
                (OUT / 'java' / f'{name}.java').write_text(
                    f'// Extracted verbatim from {origin}\n{IMPORTS}\n{imports}\n'
                    f'final class {name} {{\n{CONTEXT}\nvoid example() throws Exception {{\n{body}\n}}\n}}\n', encoding='utf-8')
                check = 'javac --release 17 -Xlint:all -Werror against built JAR'
            elif language == 'kotlin' and 'dependencies {' not in code:
                imports = '\n'.join(re.findall(r'^import .*', code, re.M))
                body = re.sub(r'^import .*\n', '', code, flags=re.M)
                (OUT / 'kotlin' / f'{name}.kt').write_text(
                    f'// Extracted verbatim from {origin}\n{imports}\nsuspend fun {name}() {{\n{body}\n}}\n', encoding='utf-8')
                check = 'Kotlin compiler against built JAR'
            elif language == 'kotlin':
                with (OUT / 'dependencies.gradle.kts').open('a', encoding='utf-8') as install:
                    install.write(code + '\n')
                check = 'evaluated in scratch Gradle consumer'
            elif language == 'xml':
                xml = ET.fromstring(code)
                assert xml.tag == 'dependency', origin
                assert xml.findtext('groupId') == 'com.trustedrouter', origin
                assert xml.findtext('artifactId') == 'trusted-router', origin
                assert xml.findtext('version') == '0.3.0', origin
                check = 'parsed and matched to published Maven coordinates'
            elif language in ('bash', 'sh'):
                # Syntax-check shell snippets only where a POSIX bash exists: on Windows,
            # `bash` resolves to the WSL launcher, which fails without a distribution.
            # The Java and Kotlin snippets are the real gate and compile everywhere.
            if os.name != 'nt':
                subprocess.run(['bash', '-n'], input=code, text=True, check=True)
                check = 'shell syntax checked; Gradle tasks exercised by CI (live smoke separate)'
            elif language in ('text', 'json', 'diff'):
                if language == 'json':
                    import json
                    json.loads(code)
                check = 'data/output (not executable source)'
            else:
                raise AssertionError(f'unhandled code language {language!r} at {origin}')
            inventory.append(f'{origin}\t{language}\t{check}')
    (OUT / 'inventory.txt').write_text('\n'.join(inventory) + '\n', encoding='utf-8')
    print(f'Extracted/validated {len(inventory)} documentation fences')


if __name__ == '__main__':
    main()
