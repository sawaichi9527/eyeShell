package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Owns the `backgrounds/` directory that holds imported terminal background images and the five
 * presets bundled with eyeShell (`bg1`–`bg5`).
 *
 * The Settings dialog never points the terminal at an arbitrary file: the chosen image is copied
 * into this directory first, so moving or deleting the original does not break the setting. Only
 * files with an image extension the AWT decoders can read are accepted.
 *
 * Bundled presets are classpath resources (`/backgrounds/bg1.jpg` …). They are materialised into
 * the directory on demand so the rest of the app only ever deals with real files.
 */
object BackgroundImages {
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "gif", "bmp")

    /** Preset file names in menu order; the first one is the default background. */
    val BUNDLED_PRESETS: List<String> = listOf("bg1.jpg", "bg2.jpg", "bg3.jpg", "bg4.jpg", "bg5.jpg")

    /** Preset used when the user has never chosen a background image. */
    const val DEFAULT_PRESET: String = "bg1.jpg"

    fun defaultDirectory(): Path = EyeShellPaths.backgroundDirectory()

    fun isSupported(file: Path): Boolean {
        val name = file.fileName?.toString().orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    /** Images currently available in [directory], ordered by file name and filtered by extension. */
    fun list(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return try {
            Files.list(directory).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && isSupported(it) }
                    .sorted(compareBy { it.fileName.toString().lowercase() })
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** True when [file] currently resolves to one of the bundled presets. */
    fun presetNameOf(file: Path?): String? {
        val name = file?.fileName?.toString() ?: return null
        return BUNDLED_PRESETS.firstOrNull { it == name }
    }

    /** True when the preset resource shipped with this build. */
    fun hasBundledPreset(name: String): Boolean = openBundled(name) != null

    /**
     * Copies a bundled preset into [directory] unless a file with that name is already there, and
     * returns the stored path. Returns `null` when the resource is missing or cannot be written.
     */
    fun materializePreset(name: String, directory: Path): Path? {
        require(name in BUNDLED_PRESETS) { "Unknown background preset: $name" }
        return try {
            Files.createDirectories(directory)
            val target = directory.resolve(name)
            if (Files.isRegularFile(target) && Files.size(target) > 0) return target
            val resource = openBundled(name) ?: return null
            resource.use { input ->
                val temporary = Files.createTempFile(directory, "$name.", ".tmp")
                try {
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
                    try {
                        Files.move(
                            temporary,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                    } catch (_: Exception) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (failure: Exception) {
                    Files.deleteIfExists(temporary)
                    throw failure
                }
            }
            target
        } catch (_: Exception) {
            null
        }
    }

    /** Materialises every bundled preset, then returns presets first and other images by name. */
    fun listWithPresets(directory: Path): List<Path> {
        val presets = BUNDLED_PRESETS.mapNotNull { materializePreset(it, directory) }
        val presetNames = presets.map { it.fileName.toString() }.toSet()
        val others = list(directory).filter { it.fileName.toString() !in presetNames }
        return presets + others
    }

    /** Copies every bundled preset into [directory] when it is not there yet. */
    fun materializePresets(directory: Path): List<Path> =
        BUNDLED_PRESETS.mapNotNull { materializePreset(it, directory) }

    /** Materialises [DEFAULT_PRESET] and returns its path, or `null` when unavailable. */
    fun ensureDefaultPreset(directory: Path = defaultDirectory()): Path? =
        materializePreset(DEFAULT_PRESET, directory)

    /**
     * Resolves a stored `terminal.background.image` value. A bundled preset whose file was deleted is
     * materialised again next to the stored path; a missing user image is returned unchanged so the
     * terminal falls back to the plain scheme background and the Settings page can mark it missing.
     */
    fun resolveStored(storedPath: String, directory: Path = defaultDirectory()): Path? {
        if (storedPath.isBlank()) return null
        val path = runCatching { Path.of(storedPath) }.getOrNull() ?: return null
        if (Files.isRegularFile(path)) return path
        val preset = presetNameOf(path) ?: return path
        return materializePreset(preset, path.parent ?: directory) ?: path
    }

    private fun openBundled(name: String): java.io.InputStream? =
        BackgroundImages::class.java.getResourceAsStream("/backgrounds/$name")

    /**
     * Copies [source] into [directory], returning the stored file.
     *
     * A file that already lives in the destination directory is returned unchanged, and an existing
     * name is never overwritten: a `-1`, `-2`, … suffix is added instead.
     */
    fun import(source: Path, directory: Path): Path {
        require(isSupported(source)) { "Unsupported background image type: ${source.fileName}" }
        require(Files.isRegularFile(source)) { "Background image does not exist: $source" }
        Files.createDirectories(directory)

        val sourceDirectory = source.toAbsolutePath().normalize().parent
        val destinationDirectory = directory.toAbsolutePath().normalize()
        if (sourceDirectory == destinationDirectory) return source

        val name = source.fileName.toString()
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var candidate = destinationDirectory.resolve(name)
        var index = 1
        while (Files.exists(candidate)) {
            candidate = destinationDirectory.resolve("$stem-$index.$extension")
            index += 1
        }
        Files.copy(source, candidate, StandardCopyOption.COPY_ATTRIBUTES)
        return candidate
    }
}

