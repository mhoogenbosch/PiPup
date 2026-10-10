package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/// Guards the bundled ISRG Root X1 used by the self-updater on Android < 7.1.1 (#41).
class IsrgRootTest {
    private fun der() = UpdateManager.pemToDer(UpdateManager.ISRG_ROOT_X1_PEM) {
        Base64.getDecoder().decode(it)
    }

    @Test
    fun derStartsWithAsn1Sequence() {
        // Android 6's Conscrypt sniffs the first byte: '-' = PEM, anything else = DER.
        // The bytes we hand it must therefore be real DER (SEQUENCE tag 0x30).
        assertEquals(0x30, der()[0].toInt() and 0xff)
    }

    @Test
    fun fingerprintMatchesPublishedIsrgRootX1() {
        val sha = MessageDigest.getInstance("SHA-256").digest(der())
            .joinToString(":") { "%02X".format(it) }
        assertEquals(
            "96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6",
            sha
        )
    }

    @Test
    fun parsesAsX509() {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(der().inputStream()) as X509Certificate
        assertEquals("CN=ISRG Root X1, O=Internet Security Research Group, C=US", cert.subjectX500Principal.name.let {
            it.split(",").joinToString(", ") { p -> p.trim() }
        })
    }
}
