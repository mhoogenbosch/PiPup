package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFallbackTest {

    @Test
    fun recognizesSoftwareAndHardwareDecoderNames() {
        assertTrue(isSoftwareVideoDecoderName("c2.android.avc.decoder"))
        assertTrue(isSoftwareVideoDecoderName("OMX.google.h264.decoder"))
        assertTrue(isSoftwareVideoDecoderName("c2.google.hevc.decoder"))
        assertTrue(isSoftwareVideoDecoderName("OMX.sec.avc.sw.dec"))

        assertFalse(isSoftwareVideoDecoderName("OMX.MTK.VIDEO.DECODER.AVC"))
        assertFalse(isSoftwareVideoDecoderName("c2.mtk.avc.decoder"))
        assertFalse(isSoftwareVideoDecoderName("OMX.amlogic.avc.decoder.awesome"))
        assertFalse(isSoftwareVideoDecoderName("OMX.qcom.video.decoder.avc"))
    }

    @Test
    fun providesStandardSoftwareCodecFallbacksByMimeType() {
        assertEquals(listOf("OMX.google.h264.decoder", "c2.android.avc.decoder"),
            fallbackSoftwareCodecNames("video/avc"))
        assertEquals(listOf("OMX.google.hevc.decoder", "c2.android.hevc.decoder"),
            fallbackSoftwareCodecNames("video/hevc"))
        assertTrue(fallbackSoftwareCodecNames("video/unknown").isEmpty())
    }
}
