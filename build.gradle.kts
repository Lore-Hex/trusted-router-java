plugins {
    `java-library`
    jacoco
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
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
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
    options.release.set(8)
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
