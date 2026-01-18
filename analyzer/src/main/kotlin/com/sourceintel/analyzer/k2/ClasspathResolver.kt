package com.sourceintel.analyzer.k2

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.nio.file.FileSystems
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Configuration for classpath resolution.
 * Controls which JAR sources to include and patterns to exclude.
 */
data class ClasspathConfig(
    val useGradleCache: Boolean = true,
    val useMavenLocal: Boolean = true,
    val useBuildDir: Boolean = true,
    val excludePatterns: List<String> = emptyList()
) {
    companion object {
        val DEFAULT = ClasspathConfig()
        
        fun fromFlags(
            excludeGradleCache: Boolean = false,
            excludeMavenLocal: Boolean = false,
            excludeBuildDir: Boolean = false,
            excludeLibPatterns: List<String> = emptyList()
        ) = ClasspathConfig(
            useGradleCache = !excludeGradleCache,
            useMavenLocal = !excludeMavenLocal,
            useBuildDir = !excludeBuildDir,
            excludePatterns = excludeLibPatterns
        )
    }
}

/**
 * Resolves classpath JARs for K2 analysis from various sources.
 * 
 * Supported sources:
 * - Gradle cache (~/.gradle/caches/modules-2/files-2.1/...)
 * - Maven local (~/.m2/repository/...)
 * - Project build directory (project/build/libs/...)
 */
class ClasspathResolver(private val config: ClasspathConfig = ClasspathConfig.DEFAULT) {
    
    private val userHome = Path.of(System.getProperty("user.home"))
    
    /**
     * Resolves all dependency JARs for the given project.
     * Recursively scans for build.gradle and build.gradle.kts files.
     */
    fun resolveClasspath(projectPath: Path): List<Path> {
        val buildFiles = projectPath.walk()
            .filter { it.name == "build.gradle" || it.name == "build.gradle.kts" }
            .filter { path ->
                // Ensure we don't pick up files inside build/ or .gradle/ directories
                // Check all parent segments
                path.parent?.any { it.toString() == "build" || it.toString() == ".gradle" } != true
            }
            .toList()
            
        logger.info { "Found ${buildFiles.size} build files" }
        
        val allDependencies = mutableListOf<Dependency>()
        
        // First pass: Collect root variables (often defined in root build.gradle)
        val globalVars = mutableMapOf<String, String>()
        val rootBuildFile = buildFiles.find { it.parent == projectPath }
        if (rootBuildFile != null) {
            extractVariables(rootBuildFile, globalVars)
        }
        
        for (buildFile in buildFiles) {
             val deps = parseBuildGradle(buildFile, globalVars)
             allDependencies.addAll(deps)
        }
        
        logger.info { "Found ${allDependencies.size} total declared dependencies" }
        
        val resolvedJars = mutableListOf<Path>()
        
        for (dep in allDependencies) {
            val jar = findJar(dep, projectPath)
            if (jar != null) {
                if (!isExcluded(jar)) {
                    resolvedJars.add(jar)
                } else {
                    logger.debug { "Excluded JAR by pattern: ${jar.name}" }
                }
            } else {
                logger.debug { "Could not resolve JAR for: $dep" }
            }
        }
        
        logger.info { "Resolved ${resolvedJars.size} JARs from ${allDependencies.size} dependencies" }
        return resolvedJars
    }

    /**
     * Gets Kotlin stdlib and runtime JARs needed for basic analysis.
     */
    fun getKotlinStdlibJars(): List<Path> {
        val kotlinVersion = "2.1.21"
        val stdlibDeps = listOf(
            Dependency("org.jetbrains.kotlin", "kotlin-stdlib", kotlinVersion),
            Dependency("org.jetbrains.kotlin", "kotlin-stdlib-common", kotlinVersion),
            Dependency("org.jetbrains", "annotations", "13.0")
        )
        
        return stdlibDeps.mapNotNull { findJar(it, null) }
            .filter { !isExcluded(it) }
    }
    
    private fun isExcluded(jar: Path): Boolean {
        val jarName = jar.name
        return config.excludePatterns.any { pattern ->
            matchesGlob(jarName, pattern)
        }
    }
    
    private fun matchesGlob(name: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return name.matches(Regex(regex, RegexOption.IGNORE_CASE))
    }

    private fun extractVariables(buildFile: Path, varMap: MutableMap<String, String>) {
        val content = buildFile.readText()
        
        // Groovy: ext.var = 'val' or def var = 'val' or var = 'val'
        // Kotlin: val var = "val" or extra["var"] = "val"
        
        val patterns = listOf(
            Regex("""ext\.(\w+)\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""def\s+(\w+)\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""val\s+(\w+)\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""extra\["(\w+)"\]\s*=\s*['"]([^'"]+)['"]""")
        )
        
        patterns.forEach { regex ->
            regex.findAll(content).forEach { match ->
                varMap[match.groupValues[1]] = match.groupValues[2]
            }
        }
    }

    /**
     * Parses build.gradle.kts or build.gradle to extract dependencies.
     */
    private fun parseBuildGradle(buildFile: Path, globalVars: Map<String, String>): List<Dependency> {
        val content = buildFile.readText()
        val dependencies = mutableListOf<Dependency>()
        
        // Local variables for this file
        val localVars = globalVars.toMutableMap()
        extractVariables(buildFile, localVars)
        
        // Resolver helper
        fun resolveVersion(v: String): String {
            if (v.startsWith("$") || v.startsWith("\$")) {
                val varName = v.trimStart('$', '{').trimEnd('}')
                return localVars[varName] ?: v
            }
            return v
        }

        // 1. Match standard implementation("group:artifact:version") - KTS & Groovy
        // regex matches: implementation "group:artifact:version" (with opt parens and quotes)
        val stdDepRegex = Regex("""(?:implementation|api|compile|runtimeOnly)\s*\(?\s*["']([a-zA-Z0-9._-]+):([a-zA-Z0-9._-]+):([^'"]+)["']\s*\)?""")
        stdDepRegex.findAll(content).forEach { match ->
            val versionRaw = match.groupValues[3]
            val version = resolveVersion(versionRaw)
            
            val dep = Dependency(
                group = match.groupValues[1],
                artifact = match.groupValues[2],
                version = version
            )
            if (dep.isValid()) dependencies.add(dep)
        }
        
        // 2. Groovy map style: implementation group: '...', name: '...', version: '...'
        val mapDepRegex = Regex("""(?:implementation|api|compile)\s+group:\s*['"]([^'"]+)['"]\s*,\s*name:\s*['"]([^'"]+)['"]\s*,\s*version:\s*['"]([^'"]+)['"]""")
        mapDepRegex.findAll(content).forEach { match ->
            val versionRaw = match.groupValues[3]
            val version = resolveVersion(versionRaw)

            val dep = Dependency(
                group = match.groupValues[1],
                artifact = match.groupValues[2],
                version = version
            )
            if (dep.isValid()) dependencies.add(dep)
        }
        
        return dependencies.distinctBy { "${it.group}:${it.artifact}" }
    }
    
    /**
     * Finds a JAR file for the given dependency.
     */
    private fun findJar(dep: Dependency, projectPath: Path?): Path? {
        // Try Gradle cache
        if (config.useGradleCache) {
            findInGradleCache(dep)?.let { return it }
        }
        
        // Try Maven local
        if (config.useMavenLocal) {
            findInMavenLocal(dep)?.let { return it }
        }
        
        // Try project build directory
        if (config.useBuildDir && projectPath != null) {
            findInBuildDir(dep, projectPath)?.let { return it }
        }
        
        return null
    }
    
    private fun findInGradleCache(dep: Dependency): Path? {
        val gradleCache = userHome.resolve(".gradle/caches/modules-2/files-2.1")
        if (!gradleCache.exists()) return null
        
        val groupPath = gradleCache.resolve(dep.group).resolve(dep.artifact).resolve(dep.version)
        if (!groupPath.exists()) return null
        
        // Find JAR in hash subdirectories
        return groupPath.walk()
            .filter { it.extension == "jar" && !it.name.contains("-sources") && !it.name.contains("-javadoc") }
            .firstOrNull()
    }
    
    private fun findInMavenLocal(dep: Dependency): Path? {
        val m2Repo = userHome.resolve(".m2/repository")
        if (!m2Repo.exists()) return null
        
        val groupPath = dep.group.replace(".", "/")
        val jarPath = m2Repo
            .resolve(groupPath)
            .resolve(dep.artifact)
            .resolve(dep.version)
            .resolve("${dep.artifact}-${dep.version}.jar")
        
        return if (jarPath.exists()) jarPath else null
    }
    
    private fun findInBuildDir(dep: Dependency, projectPath: Path): Path? {
        val buildLibs = projectPath.resolve("build/libs")
        if (!buildLibs.exists()) return null
        
        return buildLibs.walk()
            .filter { it.extension == "jar" && it.name.contains(dep.artifact) }
            .firstOrNull()
    }
    
    data class Dependency(
        val group: String,
        val artifact: String,
        val version: String
    ) {
        /** Validates that the dependency coordinates are properly formed */
        fun isValid(): Boolean {
            val validPattern = Regex("^[a-zA-Z0-9._-]+$")
            return validPattern.matches(group) &&
                   validPattern.matches(artifact) &&
                   validPattern.matches(version) &&
                   !group.contains("$") &&
                   !artifact.contains("$") &&
                   !version.contains("$")
        }
    }
}
