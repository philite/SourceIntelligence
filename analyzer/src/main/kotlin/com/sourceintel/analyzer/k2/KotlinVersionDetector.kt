package com.sourceintel.analyzer.k2

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.walk

private val logger = KotlinLogging.logger {}

/**
 * Detects the Kotlin version used in a project by parsing build files.
 * 
 * Supports:
 * - Gradle Kotlin DSL (build.gradle.kts)
 * - Gradle Groovy DSL (build.gradle) 
 * - buildSrc version catalogs
 */
object KotlinVersionDetector {
    
    data class KotlinVersion(
        val major: Int,
        val minor: Int,
        val patch: Int
    ) : Comparable<KotlinVersion> {
        
        override fun compareTo(other: KotlinVersion): Int {
            val majorDiff = major.compareTo(other.major)
            if (majorDiff != 0) return majorDiff
            val minorDiff = minor.compareTo(other.minor)
            if (minorDiff != 0) return minorDiff
            return patch.compareTo(other.patch)
        }
        
        fun isK2Compatible(): Boolean {
            // K2 compiler is available in Kotlin 2.0+
            return major >= 2
        }
        
        override fun toString(): String = "$major.$minor.$patch"
        
        companion object {
            val UNKNOWN = KotlinVersion(0, 0, 0)
            val K2_MINIMUM = KotlinVersion(2, 0, 0)
            
            fun parse(versionString: String): KotlinVersion? {
                val parts = versionString.trim().split(".")
                if (parts.size < 2) return null
                
                val major = parts[0].toIntOrNull() ?: return null
                val minor = parts[1].toIntOrNull() ?: return null
                val patch = parts.getOrNull(2)?.substringBefore("-")?.toIntOrNull() ?: 0
                
                return KotlinVersion(major, minor, patch)
            }
        }
    }
    
    /**
     * Detects Kotlin version from project build files.
     * Returns null if version cannot be determined.
     */
    fun detectVersion(projectPath: Path): KotlinVersion? {
        // Check buildSrc first (common pattern for version catalogs)
        val buildSrcVersions = projectPath.resolve("buildSrc/src/main/java/Versions.kt")
        if (buildSrcVersions.exists()) {
            val version = extractFromVersionsKt(buildSrcVersions)
            if (version != null) {
                logger.info { "Detected Kotlin version from buildSrc: $version" }
                return version
            }
        }
        
        // Check buildSrc Kotlin directory
        val buildSrcVersionsKotlin = projectPath.resolve("buildSrc/src/main/kotlin/Versions.kt")
        if (buildSrcVersionsKotlin.exists()) {
            val version = extractFromVersionsKt(buildSrcVersionsKotlin)
            if (version != null) {
                logger.info { "Detected Kotlin version from buildSrc: $version" }
                return version
            }
        }
        
        // Check root build.gradle.kts
        val rootKts = projectPath.resolve("build.gradle.kts")
        if (rootKts.exists()) {
            val version = extractFromGradleKts(rootKts)
            if (version != null) {
                logger.info { "Detected Kotlin version from build.gradle.kts: $version" }
                return version
            }
        }
        
        // Check root build.gradle
        val rootGradle = projectPath.resolve("build.gradle")
        if (rootGradle.exists()) {
            val version = extractFromGradleGroovy(rootGradle)
            if (version != null) {
                logger.info { "Detected Kotlin version from build.gradle: $version" }
                return version
            }
        }
        
        // Search for any Versions.kt file
        projectPath.walk()
            .filter { it.fileName.toString() == "Versions.kt" }
            .firstOrNull()
            ?.let { versionsFile ->
                val version = extractFromVersionsKt(versionsFile)
                if (version != null) {
                    logger.info { "Detected Kotlin version from ${versionsFile.fileName}: $version" }
                    return version
                }
            }
        
        logger.warn { "Could not detect Kotlin version, assuming K2 compatible" }
        return null
    }
    
    private fun extractFromVersionsKt(file: Path): KotlinVersion? {
        val content = file.readText()
        
        // Match patterns like: const val KOTLIN = "1.6.10"
        val patterns = listOf(
            Regex("""const\s+val\s+KOTLIN\s*=\s*"([^"]+)""""),
            Regex("""const\s+val\s+kotlinVersion\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
            Regex("""val\s+kotlin\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            pattern.find(content)?.groupValues?.get(1)?.let { versionStr ->
                return KotlinVersion.parse(versionStr)
            }
        }
        return null
    }
    
    private fun extractFromGradleKts(file: Path): KotlinVersion? {
        val content = file.readText()
        
        // Match patterns like: kotlin("jvm") version "2.1.21"
        val patterns = listOf(
            Regex("""kotlin\([^)]+\)\s+version\s+"([^"]+)""""),
            Regex("""id\s*\(\s*"org\.jetbrains\.kotlin\.jvm"\s*\)\s+version\s+"([^"]+)""""),
            Regex("""kotlin-gradle-plugin:([^"]+)"""")
        )
        
        for (pattern in patterns) {
            pattern.find(content)?.groupValues?.get(1)?.let { versionStr ->
                return KotlinVersion.parse(versionStr)
            }
        }
        return null
    }
    
    private fun extractFromGradleGroovy(file: Path): KotlinVersion? {
        val content = file.readText()
        
        // Match patterns like: ext.kotlin_version = '1.6.10'
        val patterns = listOf(
            Regex("""kotlin_version\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""kotlinVersion\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""kotlin-gradle-plugin:([^'"]+)['"]""")
        )
        
        for (pattern in patterns) {
            pattern.find(content)?.groupValues?.get(1)?.let { versionStr ->
                // Handle version variable references
                if (versionStr.startsWith("$")) return@let null
                return KotlinVersion.parse(versionStr)
            }
        }
        return null
    }
}
