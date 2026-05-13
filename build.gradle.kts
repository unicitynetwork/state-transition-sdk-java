plugins {
    id("java-library")
    id("maven-publish")
    id("checkstyle")
    id("com.google.protobuf") version "0.9.4"
    id("ru.vyarus.animalsniffer") version "2.0.1"
}

group = "org.unicitylabs"
// Use version property if provided, otherwise use default
version = if (project.hasProperty("version")) {
    project.property("version").toString()
} else {
    "1.1-SNAPSHOT"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// Define configurations for different flavors
configurations {
    create("android")
    create("jvm")
}

dependencies {
    // Core dependencies that work on both platforms
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.20.0"))
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Platform-specific Guava
    compileOnly("com.google.guava:guava:33.0.0-jre")
    "android"("com.google.guava:guava:33.0.0-android")
    "jvm"("com.google.guava:guava:33.0.0-jre")

    // Animal Sniffer signature for Android API 31
    // Note: This SDK uses Java 9-11 APIs (List.of, Map.of, Stream enhancements, etc.)
    // which require core library desugaring to be enabled in consuming Android apps.
    // Apps using this SDK must add to their build.gradle:
    //   android {
    //     compileOptions {
    //       coreLibraryDesugaringEnabled true
    //     }
    //   }
    //   dependencies {
    //     coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.4'
    //   }
    signature("com.toasttab.android:gummy-bears-api-31:0.11.0@signature")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:testcontainers:1.19.8")
    testImplementation("org.testcontainers:junit-jupiter:1.19.8")
    testImplementation("org.testcontainers:mongodb:1.19.8")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation("com.google.guava:guava:33.0.0-jre")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // ✅ Cucumber for BDD
    testImplementation("io.cucumber:cucumber-java:7.27.2")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.27.2")
    testImplementation("io.cucumber:cucumber-picocontainer:7.27.2")

    // JUnit 5 Suite annotations
    testImplementation("org.junit.platform:junit-platform-suite:1.13.4")

    checkstyle("com.puppycrawl.tools:checkstyle:10.26.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
    withJavadocJar()
}

checkstyle {
    configFile = file("config/checkstyle/checkstyle.xml")
    // TODO: Clean up test checkstyle and enable it
    sourceSets = setOf(sourceSets.find { set -> set.name == "main" })
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    maxHeapSize = "1024m"
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    systemProperties(System.getProperties().toMap() as Map<String, Any>)

    filter {
        excludeTestsMatching("*CucumberTestRunner*")
        excludeTestsMatching("*Cucumber*")
    }
}

tasks.withType<Checkstyle> {
    reports {
        xml.required.set(false)
        html.required.set(true)
    }
}

tasks.register<Test>("integrationTest") {
    useJUnitPlatform {
        includeTags("integration")
    }
    maxHeapSize = "2048m"
    shouldRunAfter(tasks.test)
    systemProperty("cucumber.junit-platform.naming-strategy", "long")

    filter {
        excludeTestsMatching("*CucumberTestRunner*")
        excludeTestsMatching("*Cucumber*")
    }
}

tasks.register<Test>("tokenTests") {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    // Set the system properties first
    systemProperties = System.getProperties().toMap() as Map<String, Any>
    // Then override the specific cucumber filter (this will take precedence)
    systemProperty("cucumber.filter.tags", "@token-transfer")

    filter {
        includeTestsMatching("*CucumberTestRunner*")
    }
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("aggregatorTests") {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    // Set the system properties first
    systemProperties = System.getProperties().toMap() as Map<String, Any>
    systemProperty("cucumber.filter.tags", "@aggregator-connectivity")

    filter {
        includeTestsMatching("*CucumberTestRunner*")
    }
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("advancedTokenTests") {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    // Set the system properties first
    systemProperties = System.getProperties().toMap() as Map<String, Any>
    systemProperty("cucumber.filter.tags", "@advanced-token")

    filter {
        includeTestsMatching("*CucumberTestRunner*")
    }
    shouldRunAfter(tasks.test)
}

// ✅ Run all cucumber tests (including integration)
tasks.register<Test>("allCucumberTests") {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    systemProperties = System.getProperties().toMap() as Map<String, Any>
    systemProperty("cucumber.filter.tags", "not @ignore")

    filter {
        includeTestsMatching("*CucumberTestRunner*")
    }
    shouldRunAfter(tasks.test)
}

// ─── BDD tasks (Phase 0 of BDD migration plan) ───────────────────────────────
// Default BDD run: defers to CucumberTestRunner's static-init default tag
// filter, which is "not @nametag and not @slow and not @wip and not @ignore"
// but yields to an explicit -Dcucumber.filter.tags=<expr> override.
tasks.register<Test>("bddTest") {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    systemProperties = System.getProperties().toMap() as Map<String, Any>

    // Make BDD env vars part of the task's up-to-date cache key so that
    // switching AGGREGATOR_URL / TRUST_BASE_PATH / tag filter actually forces a
    // re-run. Without this, Gradle sees unchanged source+classpath and serves
    // a cached pass in ~3 seconds.
    inputs.property("AGGREGATOR_URL", System.getenv("AGGREGATOR_URL") ?: "")
    inputs.property("AGGREGATOR_API_KEY", System.getenv("AGGREGATOR_API_KEY") ?: "")
    inputs.property("TRUST_BASE_PATH", System.getenv("TRUST_BASE_PATH") ?: "")
    inputs.property(
        "cucumber.filter.tags",
        System.getProperty("cucumber.filter.tags") ?: ""
    )

    // Stream Cucumber's `pretty` formatter output live to the console.
    // Without this, Gradle captures the test JVM's stdout and you only see a
    // progress bar + pass/fail totals at the end.
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    filter {
        includeTestsMatching("*CucumberTestRunner*")
    }
    shouldRunAfter(tasks.test)
}

// Nametag-gated scenarios: expected to be reported as SKIPPED until UnicityId
// is ported to Java v2. See BDD_MIGRATION_PLAN.md Phase 8.
tasks.register<Test>("bddNametag") {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    systemProperties = System.getProperties().toMap() as Map<String, Any>
    systemProperty("cucumber.filter.tags", "@nametag")

    filter {
        includeTestsMatching("*CucumberTestRunner*")
    }
    shouldRunAfter(tasks.test)
}

// Performance / load scenarios — needs sharded aggregator topology.
tasks.register<Test>("bddSlow") {
    useJUnitPlatform()
    maxHeapSize = "4096m"
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    systemProperties = System.getProperties().toMap() as Map<String, Any>
    systemProperty("cucumber.filter.tags", "@slow or @shard-load")

    filter {
        includeTestsMatching("*CucumberTestRunner*")
    }
    shouldRunAfter(tasks.test)
}

// Fast subset — excludes @tree (the ~136-row 4-level-tree family that dominates
// live-aggregator runtime) in addition to the default exclusions. Use this for
// quick live-aggregator smoke runs. Full coverage: use bddTest (slow on live).
tasks.register<Test>("bddTestFast") {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    systemProperties = System.getProperties().toMap() as Map<String, Any>
    systemProperty(
        "cucumber.filter.tags",
        "not @nametag and not @slow and not @wip and not @ignore and not @tree"
    )
    inputs.property("AGGREGATOR_URL", System.getenv("AGGREGATOR_URL") ?: "")
    inputs.property("TRUST_BASE_PATH", System.getenv("TRUST_BASE_PATH") ?: "")
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    filter {
        includeTestsMatching("*CucumberTestRunner*")
    }
    shouldRunAfter(tasks.test)
}

// Everything — used for coverage sweeps. Expect nametag scenarios to skip until
// UnicityId is ported.
tasks.register<Test>("bddAll") {
    useJUnitPlatform()
    maxHeapSize = "2048m"
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    systemProperties = System.getProperties().toMap() as Map<String, Any>
    systemProperty("cucumber.filter.tags", "not @ignore")

    filter {
        includeTestsMatching("*CucumberTestRunner*")
    }
    shouldRunAfter(tasks.test)
}

// Create separate JARs for each platform
tasks.register<Jar>("androidJar") {
    archiveClassifier.set("android")
    from(sourceSets["main"].output)
    manifest {
        attributes["Target-Platform"] = "Android"
    }
}

tasks.register<Jar>("jvmJar") {
    archiveClassifier.set("jvm")
    from(sourceSets["main"].output)
    manifest {
        attributes["Target-Platform"] = "JVM"
    }
}


// Publishing configuration for JitPack
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = "java-state-transition-sdk"
            version = project.version.toString()

            // Use the Java component as base - this includes the standard JAR
            from(components["java"])

            // Add Android JAR as additional artifact with classifier
            artifact(tasks["androidJar"]) {
                classifier = "android"
            }

            // Add JVM JAR as additional artifact with classifier
            artifact(tasks["jvmJar"]) {
                classifier = "jvm"
            }

            // Simple POM configuration without XML manipulation
            pom {
                name.set("Unicity State Transition SDK")
                description.set("Unicity State Transition SDK for Android and JVM")
                url.set("https://github.com/unicitynetwork/java-state-transition-sdk")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("unicitynetwork")
                        name.set("Unicity Network")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/unicitynetwork/java-state-transition-sdk.git")
                    developerConnection.set("scm:git:ssh://github.com/unicitynetwork/java-state-transition-sdk.git")
                    url.set("https://github.com/unicitynetwork/java-state-transition-sdk")
                }
            }
        }
    }
}