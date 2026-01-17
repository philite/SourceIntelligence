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
     * Parses build.gradle.kts to find declared dependencies, then locates JARs.
     */
    fun resolveClasspath(projectPath: Path): List<Path> {
        val dependencies = parseBuildGradle(projectPath)
        logger.info { "Found ${dependencies.size} declared dependencies" }
        
        val resolvedJars = mutableListOf<Path>()
        
        for (dep in dependencies) {
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
        
        logger.info { "Resolved ${resolvedJars.size} JARs from ${dependencies.size} dependencies" }
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
    
    /**
     * Parses build.gradle.kts to extract dependencies.
     */
    private fun parseBuildGradle(projectPath: Path): List<Dependency> {
        val buildFile = projectPath.resolve("build.gradle.kts")
        if (!buildFile.exists()) {
            logger.warn { "No build.gradle.kts found at $projectPath" }
            return emptyList()
        }
        
        val content = buildFile.readText()
        val dependencies = mutableListOf<Dependency>()
        
        // Match implementation("group:artifact:version") - full literal coordinates
        val simpleDepRegex = Regex("""implementation\s*\(\s*"([a-zA-Z0-9._-]+):([a-zA-Z0-9._-]+):([a-zA-Z0-9._-]+)"\s*\)""")
        simpleDepRegex.findAll(content).forEach { match ->
            val dep = Dependency(
                group = match.groupValues[1],
                artifact = match.groupValues[2],
                version = match.groupValues[3]
            )
            if (dep.isValid()) {
                dependencies.add(dep)
            }
        }
        
        // Match runtimeOnly/api/compileOnly("group:artifact:version")
        val otherConfigRegex = Regex("""(?:runtimeOnly|api|compileOnly)\s*\(\s*"([a-zA-Z0-9._-]+):([a-zA-Z0-9._-]+):([a-zA-Z0-9._-]+)"\s*\)""")
        otherConfigRegex.findAll(content).forEach { match ->
            val dep = Dependency(
                group = match.groupValues[1],
                artifact = match.groupValues[2],
                version = match.groupValues[3]
            )
            if (dep.isValid()) {
                dependencies.add(dep)
            }
        }
        
        // Look for version variable definitions like: val analysisVersion = "2.1.21"
        val versionVars = mutableMapOf<String, String>()
        val versionVarRegex = Regex("""val\s+(\w+)\s*=\s*"([^"]+)"""")
        versionVarRegex.findAll(content).forEach { match ->
            versionVars[match.groupValues[1]] = match.groupValues[2]
        }
        
        // Match listOf patterns - look for patterns with $variable interpolation
        // e.g., listOf("group:artifact", ...).forEach { implementation("$it:$version") }
        val listOfBlockRegex = Regex("""listOf\s*\(([^)]+)\)\s*\.forEach\s*\{[^}]*\$(\w+)""")
        listOfBlockRegex.findAll(content).forEach { listMatch ->
            val listContent = listMatch.groupValues[1]
            val versionVarName = listMatch.groupValues[2]
            val version = versionVars[versionVarName]
            
            if (version != null) {
                // Extract items: "group:artifact" or "com.example:artifact"
                val itemRegex = Regex(""""([a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+)"""")
                itemRegex.findAll(listContent).forEach { itemMatch ->
                    val parts = itemMatch.groupValues[1].split(":")
                    if (parts.size == 2) {
                        val dep = Dependency(parts[0], parts[1], version)
                        if (dep.isValid()) {
                            dependencies.add(dep)
                        }
                    }
                }
            }
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
