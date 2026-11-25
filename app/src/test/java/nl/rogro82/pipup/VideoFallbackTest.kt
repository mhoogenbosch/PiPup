package nl.rogro82.pipup

import androidx.media3.common.PlaybackException
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

    @Test
    fun classifiesVideoDecoderErrors() {
        assertTrue(isVideoDecoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(isVideoDecoderError(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(isVideoDecoderError(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED))
        assertTrue(isVideoDecoderError(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
        assertTrue(isVideoDecoderError(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED))
        assertTrue(isVideoDecoderError(PlaybackException.ERROR_CODE_TIMEOUT, "android.media.MediaCodec\$CodecException"))

        assertFalse(isVideoDecoderError(PlaybackException.ERROR_CODE_TIMEOUT, "java.net.SocketTimeoutException"))
        assertFalse(isVideoDecoderError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(isVideoDecoderError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertFalse(isVideoDecoderError(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
    }

    @Test
    fun classifiesAudioTrackErrors() {
        assertTrue(isAudioTrackError(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertTrue(isAudioTrackError(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertTrue(isAudioTrackError(PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED))
        assertTrue(isAudioTrackError(PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED))

        assertFalse(isAudioTrackError(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(isAudioTrackError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
    }

    @Test
    fun bufferingStallTimeoutAllowsFullKeyframeIntervalPlusStartBuffer() {
        assertEquals(10_500L, hardwareBufferingStallTimeoutMs(null))
        assertEquals(10_000L, hardwareBufferingStallTimeoutMs(150))
        assertEquals(14_000L, hardwareBufferingStallTimeoutMs(6_000))
    }

    @Test
    fun formatsVideoErrorConciseAndCapped() {
        assertEquals("ERROR_CODE_DECODING_FAILED: OMX error",
            formatVideoError("ERROR_CODE_DECODING_FAILED", "OMX error"))
        assertEquals("ERROR_CODE_DECODING_FAILED",
            formatVideoError("ERROR_CODE_DECODING_FAILED", null))
        assertEquals("ERROR_CODE_DECODING_FAILED",
            formatVideoError("ERROR_CODE_DECODING_FAILED", "ERROR_CODE_DECODING_FAILED"))
        assertEquals(120, formatVideoError("ERROR_CODE_DECODING_FAILED", "x".repeat(200)).length)
    }
}
