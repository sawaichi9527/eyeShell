package io.github.sawaichi9527.eyeshell.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppIconsTest {
    @Test
    fun `every bundled icon size is present and decodes`() {
        AppIcons.SIZES.forEach { size ->
            val image = AppIcons.image(size)
            assertNotNull(image, "Missing bundled icon for $size px")
            assertEquals(size, image!!.getWidth(null))
            assertEquals(size, image.getHeight(null))
        }
    }

    @Test
    fun `the window icon list is ordered from the largest size`() {
        val images = AppIcons.images()

        assertEquals(AppIcons.SIZES.size, images.size)
        assertTrue(images.first().getWidth(null) >= 256)
    }
}
