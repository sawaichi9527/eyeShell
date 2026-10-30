package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BackgroundImagesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `imported images are listed by name and filtered by extension`() {
        val backgrounds = directory.resolve("backgrounds")
        Files.createDirectories(backgrounds)
        Files.writeString(backgrounds.resolve("b2.JPG"), "x")
        Files.writeString(backgrounds.resolve("b1.png"), "x")
        Files.writeString(backgrounds.resolve("notes.txt"), "x")

        val listed = BackgroundImages.list(backgrounds).map { it.fileName.toString() }

        assertEquals(listOf("b1.png", "b2.JPG"), listed)
    }

    @Test
    fun `a missing backgrounds directory lists nothing`() {
        assertEquals(emptyList<Path>(), BackgroundImages.list(directory.resolve("absent")))
    }

    @Test
    fun `import copies the image into the backgrounds directory`() {
        val source = directory.resolve("wallpaper.png")
        Files.writeString(source, "image")
        val backgrounds = directory.resolve("backgrounds")

        val stored = BackgroundImages.import(source, backgrounds)

        assertEquals(backgrounds.toAbsolutePath().normalize(), stored.parent)
        assertEquals("wallpaper.png", stored.fileName.toString())
        assertEquals("image", Files.readString(stored))
        assertTrue(Files.exists(source))
    }

    @Test
    fun `import never overwrites an existing background`() {
        val backgrounds = directory.resolve("backgrounds")
        Files.createDirectories(backgrounds)
        Files.writeString(backgrounds.resolve("wallpaper.png"), "old")
        val source = directory.resolve("wallpaper.png")
        Files.writeString(source, "new")

        val stored = BackgroundImages.import(source, backgrounds)

        assertEquals("wallpaper-1.png", stored.fileName.toString())
        assertEquals("new", Files.readString(stored))
        assertEquals("old", Files.readString(backgrounds.resolve("wallpaper.png")))
    }

    @Test
    fun `an image already inside the backgrounds directory is returned unchanged`() {
        val backgrounds = directory.resolve("backgrounds")
        Files.createDirectories(backgrounds)
        val source = backgrounds.resolve("wallpaper.png")
        Files.writeString(source, "image")

        assertEquals(source, BackgroundImages.import(source, backgrounds))
    }

    @Test
    fun `unsupported files and missing sources are rejected`() {
        val backgrounds = directory.resolve("backgrounds")
        val text = directory.resolve("notes.txt")
        Files.writeString(text, "x")

        assertThrows(IllegalArgumentException::class.java) { BackgroundImages.import(text, backgrounds) }
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundImages.import(directory.resolve("absent.png"), backgrounds)
        }
        assertTrue(BackgroundImages.isSupported(Path.of("a.JPEG")))
        assertTrue(!BackgroundImages.isSupported(Path.of("a.svg")))
    }

    @Test
    fun `the five bundled presets ship with the application`() {
        assertEquals(listOf("bg1.jpg", "bg2.jpg", "bg3.jpg", "bg4.jpg", "bg5.jpg"), BackgroundImages.BUNDLED_PRESETS)
        assertEquals("bg1.jpg", BackgroundImages.DEFAULT_PRESET)
        BackgroundImages.BUNDLED_PRESETS.forEach { name ->
            assertTrue(BackgroundImages.hasBundledPreset(name), "Missing bundled resource: $name")
        }
        assertFalse(BackgroundImages.hasBundledPreset("bg9.jpg"))
    }

    @Test
    fun `a preset is materialised into the backgrounds directory once`() {
        val backgrounds = directory.resolve("backgrounds")

        val stored = checkNotNull(BackgroundImages.materializePreset("bg1.jpg", backgrounds))

        assertTrue(Files.isRegularFile(stored))
        assertEquals("bg1.jpg", stored.fileName.toString())
        assertTrue(Files.size(stored) > 100_000, "The preset copy looks truncated")

        val modified = Files.getLastModifiedTime(stored)
        val again = BackgroundImages.materializePreset("bg1.jpg", backgrounds)
        assertEquals(stored, again)
        assertEquals(modified, Files.getLastModifiedTime(stored), "An existing preset must not be overwritten")
    }

    @Test
    fun `an existing user file with a preset name is left untouched`() {
        val backgrounds = directory.resolve("backgrounds")
        Files.createDirectories(backgrounds)
        val existing = backgrounds.resolve("bg2.jpg")
        Files.writeString(existing, "user replaced this")

        val stored = BackgroundImages.materializePreset("bg2.jpg", backgrounds)

        assertEquals(existing, stored)
        assertEquals("user replaced this", Files.readString(existing))
    }

    @Test
    fun `the default preset resolves into the given directory`() {
        val backgrounds = directory.resolve("presets")

        val stored = BackgroundImages.ensureDefaultPreset(backgrounds)

        assertEquals(backgrounds.resolve("bg1.jpg"), stored)
    }

    @Test
    fun `preset names are recognised only for bundled file names`() {
        assertEquals("bg3.jpg", BackgroundImages.presetNameOf(Path.of("C:/x/bg3.jpg")))
        assertNull(BackgroundImages.presetNameOf(Path.of("C:/x/wallpaper.jpg")))
        assertNull(BackgroundImages.presetNameOf(null))
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundImages.materializePreset("not-a-preset.jpg", directory)
        }
    }

    @Test
    fun `the preset list always starts with bg1 to bg5`() {
        val backgrounds = directory.resolve("backgrounds")

        val listed = BackgroundImages.listWithPresets(backgrounds).map { it.fileName.toString() }

        assertEquals(listOf("bg1.jpg", "bg2.jpg", "bg3.jpg", "bg4.jpg", "bg5.jpg"), listed)
    }

    @Test
    fun `user images follow the presets in the list`() {
        val backgrounds = directory.resolve("backgrounds")
        BackgroundImages.materializePresets(backgrounds)
        Files.writeString(backgrounds.resolve("zz-custom.png"), "image")

        val listed = BackgroundImages.listWithPresets(backgrounds).map { it.fileName.toString() }

        assertEquals(
            listOf("bg1.jpg", "bg2.jpg", "bg3.jpg", "bg4.jpg", "bg5.jpg", "zz-custom.png"),
            listed,
        )
    }

    @Test
    fun `a deleted preset is restored for a stored path`() {
        val backgrounds = directory.resolve("backgrounds")
        val preset = checkNotNull(BackgroundImages.materializePreset("bg4.jpg", backgrounds))
        Files.delete(preset)

        val resolved = BackgroundImages.resolveStored(preset.toString())

        assertEquals(preset, resolved)
        assertTrue(Files.isRegularFile(preset))
    }

    @Test
    fun `a missing user image is returned unchanged so the terminal can fall back`() {
        val missing = directory.resolve("wallpaper.png").toString()

        assertEquals(Path.of(missing), BackgroundImages.resolveStored(missing))
        assertNull(BackgroundImages.resolveStored(""))
        assertNull(BackgroundImages.resolveStored("   "))
    }
}
