package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Test

/// The integer `position` of /notify (multipart `values()[n]`, Jackson by ordinal) and the
/// ha-pipup integration's `POSITIONS` table both rely on the declaration order of
/// [PopupProps.Position]. This pins that order so a reorder — or an insertion anywhere but at
/// the end — fails here instead of silently moving every popup on every TV.
class PositionOrdinalTest {
    @Test
    fun ordinalsAreTheWireContract() {
        val expected = listOf(
            "TopRight",     // 0
            "TopLeft",      // 1
            "BottomRight",  // 2
            "BottomLeft",   // 3
            "Center",       // 4
            "TopCenter",    // 5  (0.22.0)
            "BottomCenter", // 6  (0.22.0)
            "CenterLeft",   // 7  (0.26.0, #62)
            "CenterRight"   // 8  (0.26.0, #62)
        )
        assertEquals(expected, PopupProps.Position.values().map { it.name })
    }
}
