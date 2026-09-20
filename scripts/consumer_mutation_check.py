#!/usr/bin/env python3
"""Fails-without-fix controls for consumer gates, isolated from the working tree."""
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
from zipfile import ZipFile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/consumer-mutations'


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    began = time.monotonic()
    results = []
    with tempfile.TemporaryDirectory(prefix='tr-java-consumer-mutations-') as temporary:
        work = Path(temporary) / 'sdk'
        repo = Path(temporary) / 'maven'
        shutil.copytree(ROOT, work, ignore=shutil.ignore_patterns('.git', '.gradle', 'build', '__pycache__'))
        wrapper = 'gradlew.bat' if os.name == 'nt' else './gradlew'
        gradle = [wrapper, '--offline', '--console=plain', '-PlocalPublication', '-PVERSION_NAME=0.3.0', '-Dmaven.repo.local=' + str(repo)]
        def run(command, name):
            result = subprocess.run(command, cwd=work, capture_output=True, text=True, timeout=300)
            (OUTPUT / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
            return result.returncode, result.stdout + result.stderr
        code, log = run(gradle + ['consumerCheck'], 'baseline')
        if code:
            print('ERROR consumer baseline; see build/consumer-mutations/baseline.log')
            return 1
        # Baseline records the installed Gradle binary used by the real scratch consumer.
        import re
        gradle_binary = re.search(r'Scratch command: (.*?gradle(?:\.bat)?) --offline', log).group(1).strip('"')
        version = '0.3.0'
        artifacts = repo / 'com/trustedrouter/trusted-router' / version
        stem = 'trusted-router-' + version
        checker = [sys.executable, 'scripts/consumer_check.py', '--repository', str(repo),
                   '--version', version, '--gradle', gradle_binary]

        def proof(name, path, changed, command, expected):
            original = path.read_bytes() if path.exists() else None
            try:
                path.parent.mkdir(parents=True, exist_ok=True)
                if changed is None:
                    path.unlink()
                else:
                    path.write_bytes(changed)
                code, log = run(command, name)
                killed = code != 0 and expected in log
                results.append({'id': name, 'status': 'KILLED' if killed else 'ERROR', 'evidence': expected})
                print(f'{results[-1]["status"]} {name}', flush=True)
                if not killed:
                    raise RuntimeError(f'control did not fail at its intended guard: {name}')
            finally:
                if original is None:
                    path.unlink(missing_ok=True)
                else:
                    path.write_bytes(original)

        def zip_bytes(path, change):
            out = io.BytesIO()
            with ZipFile(path) as source, ZipFile(out, 'w') as target:
                entries = {name: source.read(name) for name in source.namelist()}
                change(entries)
                for name, value in entries.items():
                    target.writestr(name, value)
            return out.getvalue()

        def check(name):
            return checker + ['--test', 'PublicationTests.' + name]

        # Mandatory source-set mutation: publish the resulting JAR before asserting its listing.
        proof('stray-source-set-resource', work / 'src/main/resources/stray-fixture.json', b'{}',
              gradle + ['consumerCheck'], 'publication file list drift')
        code, _ = run(gradle + ['publishToMavenLocal'], 'restore-publication')
        if code:
            raise RuntimeError('restored publication failed')
        proof('missing-publication-artifact', artifacts / (stem + '-javadoc.jar'), None,
              check('test_archive_inventory'), 'missing publication artifact')
        proof('stray-publication-classifier', artifacts / (stem + '-tests.jar'), b'fixture',
              check('test_archive_inventory'), 'unexpected publication artifact')
        for kind, suffix in [('binary', ''), ('sources', '-sources'), ('javadoc', '-javadoc')]:
            path = artifacts / (stem + suffix + '.jar')
            proof('stray-' + kind, path, zip_bytes(path, lambda z: z.update({'scripts/leak.sh': b'leak'})),
                  check('test_archive_inventory'), 'publication file list drift')
            proof('license-content-' + kind, path, zip_bytes(path, lambda z: z.update({'META-INF/LICENSE': b'wrong'})),
                  check('test_archive_inventory'), 'FAIL: test_archive_inventory')
            proof('readme-content-' + kind, path, zip_bytes(path, lambda z: z.update({'META-INF/README.md': b'wrong'})),
                  check('test_archive_inventory'), 'FAIL: test_archive_inventory')
        binary = artifacts / (stem + '.jar')
        proof('module-name', binary, zip_bytes(binary, lambda z: z.update({
            'META-INF/MANIFEST.MF': z['META-INF/MANIFEST.MF'].replace(b'com.trustedrouter.sdk', b'wrong.module')})),
              check('test_module_and_bytecode'), 'FAIL: test_module_and_bytecode')
        cls = 'com/trustedrouter/TrustedRouterClient.class'
        proof('java-floor', binary, zip_bytes(binary, lambda z: z.update({cls: z[cls][:6] + b'\x00\x3e' + z[cls][8:]})),
              check('test_module_and_bytecode'), 'FAIL: test_module_and_bytecode')
        pom = artifacts / (stem + '.pom')
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        metadata_paths = ['groupId', 'artifactId', 'version', 'name', 'description', 'url',
            'licenses/license/name', 'licenses/license/url', 'scm/url', 'scm/connection', 'scm/developerConnection',
            'developers/developer/id', 'developers/developer/name', 'developers/developer/url',
            'properties/maven.compiler.release', 'properties/documentation.url', 'properties/keywords',
            'dependencies/dependency/version']
        for path in metadata_paths:
            tree = ET.fromstring(pom.read_bytes())
            tree.find('/'.join('m:' + p for p in path.split('/')), ns).text = 'incorrect'
            proof('pom-' + path.replace('/', '-'), pom, ET.tostring(tree),
                  check('test_pom_metadata'), 'FAIL: test_pom_metadata')
        module = artifacts / (stem + '.module')
        for variant in ('apiElements', 'runtimeElements'):
            data = json.loads(module.read_bytes())
            next(v for v in data['variants'] if v['name'] == variant)['attributes']['org.gradle.jvm.version'] = 18
            proof('gradle-java-floor-' + variant, module, json.dumps(data).encode(),
                  check('test_pom_metadata'), 'FAIL: test_pom_metadata')

        # Compile the actual extracted fences, plus the checked-in standalone examples.
        readme = work / 'README.md'
        proof('readme-java', readme, readme.read_bytes().replace(b'completion.firstText()', b'completion.doesNotExist()', 1),
              gradle + ['compileExamplesJava'], 'cannot find symbol')
        proof('readme-kotlin', readme, readme.read_bytes().replace(b'\nprintln(completion.firstText())', b'\nprintln(completion.doesNotExist())', 1),
              gradle + ['compileKotlinExamples'], 'doesNotExist')
        proof('readme-install', readme, readme.read_bytes().replace(b'implementation("com.trustedrouter:', b'unknownDependency("com.trustedrouter:', 1),
              gradle + ['consumerCheck'], 'Unresolved reference')
        proof('docs-java', work / 'docs/broken-example.md', b'```java\nclient.doesNotExist();\n```\n',
              gradle + ['compileExamplesJava'], 'cannot find symbol')
        proof('unhandled-fence', work / 'docs/broken-example.md', b'```unknown\ncode\n```\n',
              gradle + ['extractDocExamples'], 'unhandled code language')
        proof('broken-json', work / 'docs/broken-example.md', b'```json\n{invalid}\n```\n',
              gradle + ['extractDocExamples'], 'JSONDecodeError')
        proof('broken-xml', readme, readme.read_bytes().replace(b'<groupId>', b'<groupId', 1),
              gradle + ['extractDocExamples'], 'ParseError')
        for name, before, after in [
            ('root', b'<dependency>', b'<other>'),
            ('group', b'<groupId>com.trustedrouter</groupId>', b'<groupId>wrong</groupId>'),
            ('artifact', b'<artifactId>trusted-router</artifactId>', b'<artifactId>wrong</artifactId>')]:
            changed = readme.read_bytes().replace(before, after, 1)
            if name == 'root':
                changed = changed.replace(b'</dependency>', b'</other>', 1)
            proof('xml-' + name, readme, changed, gradle + ['extractDocExamples'], 'AssertionError')
        proof('wrong-xml-coordinate', readme, readme.read_bytes().replace(b'<version>0.3.0</version>', b'<version>9.0.0</version>', 1),
              gradle + ['extractDocExamples'], 'AssertionError')
        proof('broken-shell', readme, readme.read_bytes().replace(b'./gradlew clean check', b'if then ./gradlew clean check', 1),
              gradle + ['extractDocExamples'], 'syntax error')
        proof('unclosed-fence', readme, readme.read_bytes() + b'\n```java\nclient.nope();\n',
              gradle + ['extractDocExamples'], 'unclosed code fence')
        for language, path, task in [('java', 'examples/java/Quickstart.java', 'compileExamplesJava'),
                                      ('kotlin', 'examples/kotlin/Quickstart.kt', 'compileKotlinExamples')]:
            file = work / path
            proof('standalone-' + language, file, file.read_bytes().replace(b'.firstText()', b'.doesNotExist()'),
                  gradle + [task], 'doesNotExist')
        doc = work / 'src/main/java/com/trustedrouter/package-info.java'
        proof('doclint-missing', doc, b'package com.trustedrouter;\n', gradle + ['javadoc'], 'warning: no comment')
        proof('doclint-malformed', doc, b'/** <unclosed> */\npackage com.trustedrouter;\n', gradle + ['javadoc'], 'error: unknown tag')
        # Prove the fake-server result assertion, including editor-visible source/docs.
        script = work / 'scripts/consumer_check.py'
        proof('scratch-result', script, script.read_bytes().replace(b'if (!"PONG".equals(text))', b'if (!"WRONG".equals(text))'),
              check('test_scratch_consumer'), 'Unexpected reply: PONG')
        for name, before, after in [
            ('outside-checkout', b"TemporaryDirectory(prefix='trusted-router-consumer-')",
             b"TemporaryDirectory(prefix='trusted-router-consumer-', dir=ROOT)"),
            ('stdout-marker', b'System.out.println("CONSUMER_OK " + text)',
             b'System.out.println("WRONG_MARKER " + text)'),
            ('artifact-origin', b'it.absolutePath', b'it.name'),
            ('request-count', b'System.out.println("CONSUMER_OK " + text)',
             b'client.chatCompletions(ChatRequest.builder().message("user", "ping").build()); System.out.println("CONSUMER_OK " + text)'),
            ('request-path', b'.baseUrl(args[0])', b'.baseUrl(args[0] + "/wrong")'),
            ('request-auth', b'.apiKey("consumer-test-key")', b'.apiKey("incorrect")'),
            ('request-model', b'.model("fake-model")', b'.model("incorrect")'),
            ('request-messages', b'.message("user", "ping")', b'.message("user", "incorrect")'),
        ]:
            original = script.read_bytes()
            if original.count(before) != 1:
                raise RuntimeError('stale scratch control: ' + name)
            proof('scratch-' + name, script, original.replace(before, after, 1),
                  check('test_scratch_consumer'), 'FAIL: test_scratch_consumer')
        for kind, suffix, member, before in [
            ('sources', '-sources', 'com/trustedrouter/requests/ChatRequest.java', b'@return'),
            ('javadoc', '-javadoc', 'com/trustedrouter/TrustedRouterClient.html', b'chatCompletions')]:
            artifact = artifacts / (stem + suffix + '.jar')
            proof('editor-' + kind, artifact, zip_bytes(artifact, lambda z: z.update({member: z[member].replace(before, b'REMOVED')})),
                  check('test_scratch_consumer'), 'FAIL: test_scratch_consumer')
        code, _ = run(gradle + ['consumerCheck'], 'restored')
        if code:
            raise RuntimeError('restored baseline failed')
    report = {'seconds': round(time.monotonic() - began, 3), 'results': results}
    (OUTPUT / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'{len(results)}/{len(results)} consumer controls killed; restored baseline green')
    return 0


if __name__ == '__main__':
    sys.exit(main())
