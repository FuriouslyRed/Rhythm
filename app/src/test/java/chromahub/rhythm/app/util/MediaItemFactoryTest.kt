/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaItemFactoryTest {

    @Test
    fun `common audio extensions map to mime types`() {
        assertEquals("audio/mpeg", MediaItemFactory.mimeTypeForExtension("mp3"))
        assertEquals("audio/flac", MediaItemFactory.mimeTypeForExtension("FLAC"))
        assertEquals("audio/mp4", MediaItemFactory.mimeTypeForExtension("m4a"))
        assertEquals("audio/ogg", MediaItemFactory.mimeTypeForExtension("opus"))
        assertEquals("audio/eac3", MediaItemFactory.mimeTypeForExtension("eac3"))
        assertEquals("audio/dsd", MediaItemFactory.mimeTypeForExtension("dsf"))
    }

    @Test
    fun `unknown extension yields null`() {
        assertNull(MediaItemFactory.mimeTypeForExtension("xyz"))
        assertNull(MediaItemFactory.mimeTypeForExtension(""))
    }
}
