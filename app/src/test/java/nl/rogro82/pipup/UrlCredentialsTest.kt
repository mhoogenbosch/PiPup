package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlCredentialsTest {
    @Test
    fun urlWithoutCredentialsIsUnchanged() {
        val url = "http://192.168.1.10:1984/api/webrtc?src=door"
        assertEquals(url to null, splitUrlCredentials(url))
    }

    @Test
    fun credentialsAreSplitOff() {
        assertEquals(
            "http://192.168.1.10:1984/api/webrtc?src=door" to "user:secret",
            splitUrlCredentials("http://user:secret@192.168.1.10:1984/api/webrtc?src=door"),
        )
    }

    @Test
    fun reservedCharactersArePercentDecoded() {
        assertEquals("user:p@ss:w/rd+1", splitUrlCredentials("http://user:p%40ss%3Aw%2Frd+1@host:1984/api/webrtc?src=x").second)
    }

    @Test
    fun anAtSignAfterTheHostIsNotACredential() {
        val url = "http://host:1984/api/webrtc?src=a@b"
        assertNull(splitUrlCredentials(url).second)
    }

    @Test
    fun noSchemeIsLeftAlone() {
        assertEquals("host:1984/x" to null, splitUrlCredentials("host:1984/x"))
    }
}
