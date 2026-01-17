plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}

group = "com.sourceintel"
version = "0.1.0"

repositories {
    mavenCentral()
    google()

    maven("https://redirector.kotlinlang.org/maven/kotlin-ide-plugin-dependencies")
    
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

dependencies {
    val intellijPlatformVersion = "253.29346.379"
    listOf(
        "com.jetbrains.intellij.platform:util-rt",
        "com.jetbrains.intellij.platform:util-class-loader",
        "com.jetbrains.intellij.platform:util-text-matching",
        "com.jetbrains.intellij.platform:util",
        "com.jetbrains.intellij.platform:util-base",
        "com.jetbrains.intellij.platform:util-coroutines",
        "com.jetbrains.intellij.platform:util-xml-dom",
        "com.jetbrains.intellij.platform:core",
        "com.jetbrains.intellij.platform:core-impl",
    ).forEach {
        implementation("$it:$intellijPlatformVersion") { isTransitive = false }
    }

    val analysisVersion = "2.1.21"
    listOf(
        "org.jetbrains.kotlin:analysis-api-k2-for-ide",
        "org.jetbrains.kotlin:analysis-api-for-ide",
        "org.jetbrains.kotlin:low-level-api-fir-for-ide",
        "org.jetbrains.kotlin:analysis-api-platform-interface-for-ide",
        "org.jetbrains.kotlin:symbol-light-classes-for-ide",
        "org.jetbrains.kotlin:analysis-api-standalone-for-ide",
        "org.jetbrains.kotlin:analysis-api-impl-base-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-common-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fir-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fe10-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-ir-for-ide",
    ).forEach {
        implementation("$it:$analysisVersion") { isTransitive = false }
    }
    
    // Android Lint API - Updated to latest stable 2026 version
    implementation("com.android.tools.lint:lint-api:31.13.2")
    implementation("com.android.tools:sdk-common:31.13.2")
    
    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    
    // CLI argument parsing - Updated to 5.0.2+
    implementation("com.github.ajalt.clikt:clikt:5.0.2")
    
    // Logging - Updated to latest stable 2026 versions
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("ch.qos.logback:logback-classic:1.5.24")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.sourceintel.analyzer.MainKt")
}

tasks.test {
    useJUnitPlatform()

    systemProperty("idea.home.path", layout.buildDirectory.dir("tmp/ideaHome").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.sourceintel.analyzer.MainKt"
    }
    // Enable zip64 for large archives
    isZip64 = true
}

// Use application plugin's distribution instead of fat JAR
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

