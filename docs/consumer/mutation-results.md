# Consumer negative controls

All mutations ran in an isolated SDK copy/local Maven repository. Both baseline and restored baseline passed. Each failure required a nonzero exit and the diagnostic below.

| Mutation | Result | Required failure evidence |
| --- | --- | --- |
| stray-source-set-resource | KILLED | publication file list drift |
| missing-publication-artifact | KILLED | missing publication artifact |
| stray-publication-classifier | KILLED | unexpected publication artifact |
| stray-binary | KILLED | publication file list drift |
| license-content-binary | KILLED | FAIL: test_archive_inventory |
| readme-content-binary | KILLED | FAIL: test_archive_inventory |
| stray-sources | KILLED | publication file list drift |
| license-content-sources | KILLED | FAIL: test_archive_inventory |
| readme-content-sources | KILLED | FAIL: test_archive_inventory |
| stray-javadoc | KILLED | publication file list drift |
| license-content-javadoc | KILLED | FAIL: test_archive_inventory |
| readme-content-javadoc | KILLED | FAIL: test_archive_inventory |
| module-name | KILLED | FAIL: test_module_and_bytecode |
| java-floor | KILLED | FAIL: test_module_and_bytecode |
| pom-groupId | KILLED | FAIL: test_pom_metadata |
| pom-artifactId | KILLED | FAIL: test_pom_metadata |
| pom-version | KILLED | FAIL: test_pom_metadata |
| pom-name | KILLED | FAIL: test_pom_metadata |
| pom-description | KILLED | FAIL: test_pom_metadata |
| pom-url | KILLED | FAIL: test_pom_metadata |
| pom-licenses-license-name | KILLED | FAIL: test_pom_metadata |
| pom-licenses-license-url | KILLED | FAIL: test_pom_metadata |
| pom-scm-url | KILLED | FAIL: test_pom_metadata |
| pom-scm-connection | KILLED | FAIL: test_pom_metadata |
| pom-scm-developerConnection | KILLED | FAIL: test_pom_metadata |
| pom-developers-developer-id | KILLED | FAIL: test_pom_metadata |
| pom-developers-developer-name | KILLED | FAIL: test_pom_metadata |
| pom-developers-developer-url | KILLED | FAIL: test_pom_metadata |
| pom-properties-maven.compiler.release | KILLED | FAIL: test_pom_metadata |
| pom-properties-documentation.url | KILLED | FAIL: test_pom_metadata |
| pom-properties-keywords | KILLED | FAIL: test_pom_metadata |
| pom-dependencies-dependency-version | KILLED | FAIL: test_pom_metadata |
| gradle-java-floor-apiElements | KILLED | FAIL: test_pom_metadata |
| gradle-java-floor-runtimeElements | KILLED | FAIL: test_pom_metadata |
| readme-java | KILLED | cannot find symbol |
| readme-kotlin | KILLED | doesNotExist |
| readme-install | KILLED | Unresolved reference |
| docs-java | KILLED | cannot find symbol |
| unhandled-fence | KILLED | unhandled code language |
| broken-json | KILLED | JSONDecodeError |
| broken-xml | KILLED | ParseError |
| xml-root | KILLED | AssertionError |
| xml-group | KILLED | AssertionError |
| xml-artifact | KILLED | AssertionError |
| wrong-xml-coordinate | KILLED | AssertionError |
| broken-shell | KILLED | syntax error |
| unclosed-fence | KILLED | unclosed code fence |
| standalone-java | KILLED | doesNotExist |
| standalone-kotlin | KILLED | doesNotExist |
| doclint-missing | KILLED | warning: no comment |
| doclint-malformed | KILLED | error: unknown tag |
| scratch-result | KILLED | Unexpected reply: PONG |
| scratch-outside-checkout | KILLED | FAIL: test_scratch_consumer |
| scratch-stdout-marker | KILLED | FAIL: test_scratch_consumer |
| scratch-artifact-origin | KILLED | FAIL: test_scratch_consumer |
| scratch-request-count | KILLED | FAIL: test_scratch_consumer |
| scratch-request-path | KILLED | FAIL: test_scratch_consumer |
| scratch-request-auth | KILLED | FAIL: test_scratch_consumer |
| scratch-request-model | KILLED | FAIL: test_scratch_consumer |
| scratch-request-messages | KILLED | FAIL: test_scratch_consumer |
| editor-sources | KILLED | FAIL: test_scratch_consumer |
| editor-javadoc | KILLED | FAIL: test_scratch_consumer |

62/62 killed in 81.359 seconds.
