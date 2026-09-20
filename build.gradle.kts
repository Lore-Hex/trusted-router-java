import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    jacoco
    id("net.ltgt.errorprone") version "4.3.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "com.trustedrouter"
version = providers.gradleProperty("VERSION_NAME").orElse("0.3.0").get()
description = "Java, Kotlin, and Android SDK for TrustedRouter"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.named<JavaCompile>("compileJava") {
    options.errorprone {
        // Isolated runtime proofs must fail in the test, not in a static checker.
        if (providers.gradleProperty("mutationRuntime").isPresent) { isEnabled.set(false) }
        error("UnusedVariable", "MissingCasesInEnumSwitch", "ReturnValueIgnored",
            "FutureReturnValueIgnored", "CatchAndPrintStackTrace", "EmptyCatch", "ClassCanBeStatic")
        disable("UnnecessaryParentheses")
        option("NullAway:AnnotatedPackages", "com.trustedrouter")
        // 345 diagnostic locations / 187 uninitialized field sites; see docs/nullaway-audit.txt.
        disable("NullAway")
        if (providers.gradleProperty("nullawayAudit").isPresent) {
            error("NullAway")
        }
    }
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "1000", "-Xmaxwarns", "1000"))
}

// Tests retain javac -Xlint:all -Werror; production is the Error Prone gate.
tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.isEnabled.set(false)
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

tasks.jar {
    manifest.attributes["Automatic-Module-Name"] = "com.trustedrouter.sdk"
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.42.0")
    errorprone("com.uber.nullaway:nullaway:0.12.10")

    api("com.squareup.okhttp3:okhttp:5.3.0")
    api("com.google.code.gson:gson:2.13.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.3.0")
    testImplementation("org.assertj:assertj-core:3.27.6")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.64".toBigDecimal()
            }
        }
    }
}

val compileJavaExamples by tasks.registering(JavaCompile::class) {
    dependsOn(tasks.classes)
    source(fileTree("examples/java") { include("**/*.java") })
    classpath = sourceSets.main.get().runtimeClasspath
    destinationDirectory.set(layout.buildDirectory.dir("examples/java"))
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.register<JavaExec>("runPublicTrustSmoke") {
    dependsOn(compileJavaExamples)
    classpath = files(compileJavaExamples.map { it.destinationDirectory }) +
        sourceSets.main.get().runtimeClasspath
    mainClass.set("PublicTrustSmoke")
}

tasks.register<JavaExec>("runQuickstart") {
    dependsOn(compileJavaExamples)
    classpath = files(compileJavaExamples.map { it.destinationDirectory }) +
        sourceSets.main.get().runtimeClasspath
    mainClass.set("Quickstart")
}

tasks.register<JavaExec>("runAuthenticatedSmoke") {
    dependsOn(compileJavaExamples)
    classpath = files(compileJavaExamples.map { it.destinationDirectory }) +
        sourceSets.main.get().runtimeClasspath
    mainClass.set("AuthenticatedSmoke")
}

val boundaryCheck by tasks.registering(Exec::class) {
    commandLine("python3", "scripts/boundary_check.py")
    inputs.files(fileTree("src/main/java"), fileTree("scripts") { include("boundary*") })
}

tasks.named("compileJava") { dependsOn(boundaryCheck) }

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification, compileJavaExamples)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(group.toString(), "trusted-router", version.toString())

    pom {
        name.set("TrustedRouter Java SDK")
        description.set("Java, Kotlin, and Android SDK for TrustedRouter")
        inceptionYear.set("2026")
        url.set("https://trustedrouter.com")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("lore-hex")
                name.set("Lore Hex Corp")
                url.set("https://trustedrouter.com")
            }
        }
        scm {
            url.set("https://github.com/Lore-Hex/trusted-router-java")
            connection.set("scm:git:git://github.com/Lore-Hex/trusted-router-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/Lore-Hex/trusted-router-java.git")
        }
    }
}
