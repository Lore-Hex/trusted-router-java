# Consumer DX verification

This worktree adds consumer verification without changing production Java behavior or runtime dependencies. All production Java edits are Javadocs; a comment-stripped comparison against HEAD confirmed unchanged code. No commit was created.

## Publication inventory

These are complete, sorted ZIP member lists (files only), captured from the Vanniktech publication artifacts. The after lists are reviewed golden inventories used by `PublicationTests.test_archive_inventory`, not regenerated during tests. New API classes require an intentional inventory update.

| Artifact | Before files | After files | Before listing | After listing |
| --- | ---: | ---: | --- | --- |
| Binary JAR | 179 | 181 | [before-binary.txt](before-binary.txt) | [after-binary.txt](after-binary.txt) |
| Sources JAR | 100 | 102 | [before-sources.txt](before-sources.txt) | [after-sources.txt](after-sources.txt) |
| Javadoc JAR | 189 | 191 | [before-javadoc.txt](before-javadoc.txt) | [after-javadoc.txt](after-javadoc.txt) |

Each archive adds exactly `META-INF/LICENSE` and `META-INF/README.md`. The binary contains SDK classes, the module manifest and required consumer ProGuard rules. Sources contain SDK Java sources and the consumer rules. Javadoc contains generated API documentation and its supporting assets. No project tests, fixtures, build/CI scripts or scratch projects ship. Generated Javadoc browser JavaScript is retained as part of the documentation.

The baseline command was `./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/java2-before-repo --console=plain`. It built all three archives and the POM, then failed at signing because no signing key was configured. The before listings and [before.pom](before.pom) were captured from those build outputs. The new explicit `-PlocalPublication` option permits unsigned local verification. Release publication without this option still requires signing.

## Metadata and consumer documentation

| Metadata | Before | After |
| --- | --- | --- |
| Name, description, homepage, Apache-2.0 license, SCM URLs, developer identity/URL | Present | Preserved; asserted in the published POM |
| Keywords/topics | Absent | `trustedrouter,ai,llm,java,kotlin,android,sdk` POM property |
| Minimum Java version | Java 17 bytecode and Gradle variant attributes | Also `maven.compiler.release=17` in POM; bytecode and both variants asserted |
| Documentation URL | Absent | `https://javadoc.io/doc/com.trustedrouter/trusted-router` POM property and README link |
| JPMS name | `com.trustedrouter.sdk` | Preserved and asserted in the binary manifest |
| Source/Javadoc classifiers | Present; missing API comments not checked | Resolved by scratch Gradle consumer; source comments and generated API page asserted |
| Javadoc validation | `-Xdoclint:all,-missing` | `-Xdoclint:all -Werror`, zero warnings |
| Runtime dependencies | OkHttp 5.3.0, Gson 2.13.2 | Unchanged and asserted exactly in the POM |

Kotlin compiler and coroutine dependencies are confined to example verification configurations. Public signatures, parameters, return values, exceptions and type parameters now have Javadocs. The checked-in [after.pom](after.pom) records the resulting metadata.

## Examples and CLI coverage

| Surface | Before | After |
| --- | --- | --- |
| README/docs Java fences | Not extracted | 20 extracted and compiled with Java 17, all lint warnings fatal, against the built JAR |
| README Kotlin example | Not compiled | Extracted and compiled with warnings fatal against the built JAR |
| Gradle installation fence | Not evaluated | Evaluated by the scratch consumer |
| Maven installation fence | Not checked | XML parsed and coordinates checked |
| Shell command fences | Not checked | Both syntax checked; deterministic build command runs in CI; live smoke source compiles |
| `examples/java/` | Compiled against source-tree classes | Three examples compiled in the `examples` source set against the built JAR |
| `examples/kotlin/` | Not compiled | Standalone coroutine quickstart compiled against the built JAR |
| CLI command × option coverage | N/A: SDK has no CLI | N/A: no CLI added |

The extractor scans README and every Markdown file under docs, rejects unhandled languages/unclosed fences, and writes an origin inventory. The [origin inventory](examples.txt) lists all 25 fences. Java fragments receive explicitly typed surrounding application values and real SDK imports; no SDK classes are stubbed. Android callback input is a URI from the application. Responses streaming now declares its own `ResponsesRequest`. Live, credential-dependent production calls are not executed by the deterministic consumer check.

## Exact local commands and scratch consumer

Environment used: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`; `export PATH="$JAVA_HOME/bin:$PATH"`; `export GRADLE_USER_HOME=/private/tmp/tr-java-gradle`. The temporary Gradle cache avoids the sandbox's read-only default cache.

From the SDK worktree:

1. `./gradlew clean compileJava check javadoc -PlocalPublication -Dmaven.repo.local=/tmp/java2-maven --rerun-tasks --console=plain`
2. `python3 scripts/mutation_check.py`
3. `python3 scripts/static_gate_check.py`
4. `python3 scripts/consumer_mutation_check.py`
5. `../../conf-venv/bin/tr-conformance --sdk java --sdk-root java="$PWD" --allowed-skips none --json-report build/consumer-conformance.json`

`consumerCheck` depends on Vanniktech's `publishToMavenLocal`, Java examples and Kotlin examples. Its Python unittest creates a temporary Gradle application outside the SDK checkout, configures `mavenLocal()` with the isolated repository, and injects the extracted README dependency declaration. It resolves the SDK binary, source and Javadoc classifiers from that exact local artifact directory. It compiles a Java 17 call and runs it against a loopback fake server, requiring exactly one authenticated chat request with the expected model/messages and a `PONG` response. Both URLs are local and telemetry is disabled. The project is deleted on completion.

The actual scratch Gradle invocation is recorded in [verification.txt](verification.txt). The final packaging-only adjustment also passed `check javadoc`; see [publication-check.txt](publication-check.txt). Its arguments are `--offline --no-daemon --console=plain -Dmaven.repo.local=/private/tmp/java2-maven run --args=http://127.0.0.1:PORT/v1 resolveEditorArtifacts`, using the Gradle binary installed by this repository's wrapper. PORT is the OS-assigned loopback port. Reproduce the entire scratch setup, compilation, run and cleanup with `./gradlew consumerCheck -PlocalPublication -Dmaven.repo.local=/tmp/java2-maven`.

## Fails-without-fix and verification results

Run the commands sequentially: the conformance composite build shares the SDK output directory, and Gradle `clean` removes in-progress verification logs. An overlapping exploratory CI run encountered missing compiled classes; it was discarded and replaced by a clean, forced full CI run after conformance finished.

Final clean CI: **256 tests, zero failures/errors, two credential-gated live skips; 82.95% line coverage** (3,818/4,603 lines). All 20 Gradle tasks were forced to execute and passed, including zero-warning full doclint and four publication/scratch unittest checks. SDK conformance: **25/25 passed, zero skipped**.

**Consumer negative controls: 62/62 killed; unmodified and restored baselines passed.** The [per-mutation table](mutation-results.md) records every guard and its failure evidence.

Results are recorded in [consumer-mutations.json](consumer-mutations.json), [wave1-mutations.json](wave1-mutations.json), [static-controls.json](static-controls.json), [conformance.txt](conformance.txt), and [verification.txt](verification.txt). Consumer controls mutate an isolated SDK copy/local repository and restore exact bytes after each probe. They require both a nonzero exit and an expected diagnostic from the intended guard; the unmodified baseline and restored baseline must pass.

The mandatory stray-file control adds `src/main/resources/stray-fixture.json`, republishes, and fails the archive listing assertion. Separate controls alter each archive, license/readme payloads, module name, Java bytecode floor, each required POM field, runtime dependency metadata, both Gradle variant floors, README Java/Kotlin/installation examples, docs examples, standalone Java/Kotlin examples, XML/shell/fence validation, doclint, scratch response/output, scratch location and artifact origin, request count/path/authorization/model/messages, and editor source/Javadoc content. There are no CLI controls because there is no CLI.

Wave 1 killed 29/29 mutants in 428.19 seconds; the script emitted its advisory about exceeding five minutes during concurrent isolated runs, but its gate passed. All 14 static negative controls were rejected in 29.309 seconds.

The two existing credential-gated receipt live smoke tests remain skipped. They are separate from deterministic tests and the SDK conformance suite.

Every changed file and changed hunk is indexed with file:line references in [change-locations.md](change-locations.md).
