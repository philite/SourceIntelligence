---
description: Kotlin K2 Analysis API usage patterns and limitations
---

# Kotlin K2 Analysis API Knowledge

## Overview
The K2 Analysis API is JetBrains' new API for Kotlin compiler analysis, replacing the older K1 (FE10) approach. It uses FIR (Frontend Intermediate Representation) for semantic analysis.

## Key Components

### 1. StandaloneAnalysisAPISession
Entry point for standalone (non-IDE) analysis.

```kotlin
val session = buildStandaloneAnalysisAPISession(projectDisposable) {
    buildKtModuleProvider {
        platform = JvmPlatforms.defaultJvmPlatform
        addModule(buildKtSourceModule { ... })
    }
}
```

### 2. KaSession Context
All symbol resolution requires being inside a `KaSession` context:

```kotlin
analyze(ktFile) { // Inside KaSession
    val symbol = ktClass.symbol // .symbol only available here
    val fqn = symbol.classId?.asFqNameString()
}
```

### 3. Dependencies Required (Kotlin 2.1.21)
```kotlin
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
    implementation("$it:$kotlinVersion") { isTransitive = false }
}
```

Maven repos required:
```kotlin
maven("https://redirector.kotlinlang.org/maven/kotlin-ide-plugin-dependencies")
maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
```

## Limitations Discovered

### 1. Standalone Mode Requires IntelliJ Platform
`KotlinCoreEnvironment.createForProduction()` fails standalone:
```
IllegalStateException: Unable to find extension point configuration extensions/compiler.xml
```

This is because the environment expects IntelliJ platform extension points.

### 2. PSI vs K2 Analysis
- **PSI (KtPsiFactory)**: Syntax tree only, no type resolution
- **K2 Analysis**: Full semantic analysis, but requires proper setup

### 3. Symbol Extensions Require KaSession
The `.symbol` extension properties on PSI elements like `KtClass.symbol` only work inside an `analyze {}` block which provides the `KaSession` context.

```kotlin
// WRONG - outside KaSession
val symbol = ktClass.symbol // Compile error

// CORRECT - inside KaSession
analyze(ktFile) {
    val symbol = ktClass.symbol // Works
}
```

### 4. Library Resolution Needs Classpath
To resolve external library types (like `CliktCommand`), you must:
1. Build with `buildKtLibraryModule` for each dependency JAR
2. Add them as `regularDependency` to source module
3. JARs must be actual files on disk

## Workarounds

### For Simple Analysis
Use regex-based parsing with PSI for structure:
1. Find Kotlin files
2. Parse with regex for class declarations
3. Create placeholder nodes for unresolved external types
4. Track external types by name, create nodes post-analysis

### For Full Semantic Analysis
Would require:
1. Running inside IntelliJ/Android Studio plugin
2. Or extensive setup of IntelliJ platform components
3. Or waiting for better standalone support from JetBrains

## Resources
- Source code: https://github.com/JetBrains/kotlin/tree/v2.1.21/analysis
- Key files:
  - `StandaloneAnalysisAPISessionBuilder.kt`
  - `StandaloneProjectFactory.kt`
  - `KtModuleProviderBuilder.kt`