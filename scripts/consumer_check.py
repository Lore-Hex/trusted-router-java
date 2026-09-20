#!/usr/bin/env python3
"""Test the published Maven Local artifacts and an isolated Gradle consumer."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
PARSER = argparse.ArgumentParser(description=__doc__)
PARSER.add_argument('--repository', type=Path, required=True)
PARSER.add_argument('--version', required=True)
PARSER.add_argument('--gradle', required=True)
PARSER.add_argument('--test', help='Run a single publication test for negative controls')
ARGS = PARSER.parse_args()
REPO = ARGS.repository.resolve()
ARTIFACTS = REPO / 'com/trustedrouter/trusted-router' / ARGS.version
STEM = 'trusted-router-' + ARGS.version


class PublicationTests(unittest.TestCase):
    def test_archive_inventory(self):
        expected_files = {STEM + suffix for suffix in (
            '.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module')}
        actual_files = {p.name for p in ARTIFACTS.iterdir()}
        self.assertTrue(expected_files <= actual_files, 'missing publication artifact')
        self.assertFalse(actual_files - expected_files - {name + '.asc' for name in expected_files},
                         'unexpected publication artifact')
        for kind, suffix in [('binary', ''), ('sources', '-sources'), ('javadoc', '-javadoc')]:
            with self.subTest(kind=kind), ZipFile(ARTIFACTS / (STEM + suffix + '.jar')) as jar:
                actual = sorted(n for n in jar.namelist() if not n.endswith('/'))
                expected = (ROOT / f'docs/consumer/after-{kind}.txt').read_text().splitlines()
                self.assertEqual(actual, expected, f'{kind} publication file list drift')
                for name in ('LICENSE', 'README.md'):
                    self.assertEqual(jar.read('META-INF/' + name), (ROOT / name).read_bytes())

    def test_module_and_bytecode(self):
        with ZipFile(ARTIFACTS / (STEM + '.jar')) as jar:
            self.assertIn('Automatic-Module-Name: com.trustedrouter.sdk', jar.read('META-INF/MANIFEST.MF').decode())
            for name in jar.namelist():
                if name.endswith('.class'):
                    self.assertEqual(int.from_bytes(jar.read(name)[6:8], 'big'), 61, name)

    def test_pom_metadata(self):
        pom = ET.parse(ARTIFACTS / (STEM + '.pom'))
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        expected = {
            'groupId': 'com.trustedrouter', 'artifactId': 'trusted-router', 'version': ARGS.version,
            'name': 'TrustedRouter Java SDK',
            'description': 'Java, Kotlin, and Android SDK for TrustedRouter',
            'url': 'https://trustedrouter.com',
            'licenses/license/name': 'The Apache License, Version 2.0',
            'licenses/license/url': 'https://www.apache.org/licenses/LICENSE-2.0.txt',
            'scm/url': 'https://github.com/Lore-Hex/trusted-router-java',
            'scm/connection': 'scm:git:git://github.com/Lore-Hex/trusted-router-java.git',
            'scm/developerConnection': 'scm:git:ssh://git@github.com/Lore-Hex/trusted-router-java.git',
            'developers/developer/id': 'lore-hex', 'developers/developer/name': 'Lore Hex Corp',
            'developers/developer/url': 'https://trustedrouter.com',
            'properties/maven.compiler.release': '17',
            'properties/documentation.url': 'https://javadoc.io/doc/com.trustedrouter/trusted-router',
            'properties/keywords': 'trustedrouter,ai,llm,java,kotlin,android,sdk',
        }
        for path, value in expected.items():
            with self.subTest(path=path):
                self.assertEqual(pom.findtext('/'.join('m:' + p for p in path.split('/')), namespaces=ns), value)
        dependencies = {(d.findtext('m:groupId', namespaces=ns), d.findtext('m:artifactId', namespaces=ns),
                         d.findtext('m:version', namespaces=ns), d.findtext('m:scope', namespaces=ns))
                        for d in pom.findall('m:dependencies/m:dependency', ns)}
        self.assertEqual(dependencies, {('com.squareup.okhttp3', 'okhttp', '5.3.0', 'compile'),
                                        ('com.google.code.gson', 'gson', '2.13.2', 'compile')})
        module = json.loads((ARTIFACTS / (STEM + '.module')).read_text())
        floors = {variant['name']: variant['attributes'].get('org.gradle.jvm.version')
                  for variant in module['variants'] if variant['name'] in ('apiElements', 'runtimeElements')}
        self.assertEqual(floors, {'apiElements': 17, 'runtimeElements': 17})

    def test_scratch_consumer(self):
        captured = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                captured.append((self.path, self.headers.get('Authorization'),
                                 json.loads(self.rfile.read(int(self.headers['Content-Length'])))))
                body = b'{"id":"consumer","choices":[{"message":{"role":"assistant","content":"PONG"}}]}'
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *args):
                pass
        with tempfile.TemporaryDirectory(prefix='trusted-router-consumer-') as temporary:
            work = Path(temporary)
            self.assertNotIn(ROOT, work.parents)
            with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
                worker = threading.Thread(target=server.serve_forever, daemon=True)
                worker.start()
                try:
                    (work / 'settings.gradle.kts').write_text('rootProject.name = "scratch-consumer"\n')
                    # Evaluate the actual README install snippet, substituting only the release
                    # version so release-tag CI verifies the artifact it just built.
                    install = (ROOT / 'build/generated/examples/dependencies.gradle.kts').read_text()
                    install = install.replace(':0.3.0"', ':' + ARGS.version + '"')
                    (work / 'build.gradle.kts').write_text('''
plugins { application }
repositories { mavenLocal(); mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
application { mainClass.set("Consumer") }
val sdkSources by configurations.creating { isTransitive = false }
val sdkDocs by configurations.creating { isTransitive = false }
''' + install + f'''
dependencies {{
    sdkSources("com.trustedrouter:trusted-router:{ARGS.version}:sources@jar")
    sdkDocs("com.trustedrouter:trusted-router:{ARGS.version}:javadoc@jar")
}}
tasks.register("resolveEditorArtifacts") {{
    doLast {{
        file("resolved.txt").writeText((sdkSources.files + sdkDocs.files + configurations.runtimeClasspath.get().files).joinToString("\\n") {{ it.absolutePath }})
    }}
}}
''', encoding='utf-8')
                    source = work / 'src/main/java/Consumer.java'
                    source.parent.mkdir(parents=True)
                    source.write_text('''
import com.trustedrouter.TrustedRouterClient;
import com.trustedrouter.TrustedRouterOptions;
import com.trustedrouter.requests.ChatRequest;
public final class Consumer {
    private Consumer() {}
    public static void main(String[] args) throws Exception {
        try (TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("consumer-test-key").baseUrl(args[0]).controlBaseUrl(args[0])
                .maxRetries(0).telemetry(Boolean.FALSE).build())) {
            String text = client.chatCompletions(ChatRequest.builder()
                    .model("fake-model").message("user", "ping").build()).firstText();
            if (!"PONG".equals(text)) throw new AssertionError("Unexpected reply: " + text);
            System.out.println("CONSUMER_OK " + text);
        }
    }
}
''', encoding='utf-8')
                    command = [ARGS.gradle, '--offline', '--no-daemon', '--console=plain',
                               '-Dmaven.repo.local=' + str(REPO), 'run',
                               '--args=http://127.0.0.1:' + str(server.server_port) + '/v1',
                               'resolveEditorArtifacts']
                    print('Scratch directory:', work, flush=True)
                    print('Scratch command:', subprocess.list2cmdline(command), flush=True)
                    result = subprocess.run(command, cwd=work, capture_output=True, text=True, timeout=240)
                    report = ROOT / 'build/consumer-results'
                    report.mkdir(exist_ok=True)
                    (report / 'scratch.log').write_text(result.stdout + result.stderr, encoding='utf-8')
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn('CONSUMER_OK PONG', result.stdout)
                    resolved = (work / 'resolved.txt').read_text().splitlines()
                    for suffix in ('', '-sources', '-javadoc'):
                        self.assertIn(str(ARTIFACTS / (STEM + suffix + '.jar')), resolved)
                    with ZipFile(ARTIFACTS / (STEM + '-sources.jar')) as jar:
                        self.assertIn(b'@return', jar.read('com/trustedrouter/requests/ChatRequest.java'))
                    with ZipFile(ARTIFACTS / (STEM + '-javadoc.jar')) as jar:
                        self.assertIn(b'chatCompletions', jar.read('com/trustedrouter/TrustedRouterClient.html'))
                    self.assertEqual(len(captured), 1)
                    self.assertEqual(captured[0][0:2], ('/v1/chat/completions', 'Bearer consumer-test-key'))
                    self.assertEqual(captured[0][2]['model'], 'fake-model')
                    self.assertEqual(captured[0][2]['messages'], [{'role': 'user', 'content': 'ping'}])
                finally:
                    server.shutdown()
                    worker.join()


if __name__ == '__main__':
    unittest.main(argv=[__file__] + ([ARGS.test] if ARGS.test else []), verbosity=2, failfast=True)
