package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The pure part of the MQTT transport (1.1.0): topics, payloads, config, answers.
class MqttRoutingTest {

    @Test
    fun commandTopicOfThisTv() {
        assertEquals("notify", parseCommandTopic("pipup", "tv1", "pipup/tv1/cmd/notify"))
        assertEquals("media/seek", parseCommandTopic("pipup", "tv1", "pipup/tv1/cmd/media/seek"))
    }

    @Test
    fun broadcastTopicReachesEveryTv() {
        assertEquals("cancel", parseCommandTopic("pipup", "tv1", "pipup/all/cmd/cancel"))
    }

    @Test
    fun foreignTopicsAreNotCommands() {
        assertNull(parseCommandTopic("pipup", "tv1", "pipup/tv2/cmd/notify"))     // another TV
        assertNull(parseCommandTopic("pipup", "tv1", "other/tv1/cmd/notify"))     // other prefix
        assertNull(parseCommandTopic("pipup", "tv1", "pipup/tv1/state"))          // our own output
        assertNull(parseCommandTopic("pipup", "tv1", "pipup/tv1/cmd"))            // no endpoint
        assertNull(parseCommandTopic("pipup", "tv1", "pipup/tv1/cmd/"))           // empty endpoint
        assertNull(parseCommandTopic("pipup", "tv1", "pipup/tv1/cmd/+"))          // wildcard
        assertNull(parseCommandTopic("pipup", "tv1", "pipup/tv1/cmd/a//b"))       // empty level
    }

    @Test
    fun emptyPayloadMeansNoParameters() {
        assertEquals(emptyMap<String, List<String>>(), payloadToParams(ByteArray(0)))
        assertEquals(emptyMap<String, List<String>>(), payloadToParams(" \n".toByteArray()))
    }

    @Test
    fun objectFieldsBecomeParameters() {
        val params = payloadToParams("""{"state":"on","level":7,"ui":false,"id":null,"keys":["a","b"]}""".toByteArray())
        assertEquals(listOf("on"), params["state"])
        assertEquals(listOf("7"), params["level"])
        assertEquals(listOf("false"), params["ui"])
        assertNull(params["id"])
        assertEquals(listOf("a", "b"), params["keys"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun bareStringPayloadIsRefused() {
        payloadToParams("\"on\"".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun brokenJsonIsRefused() {
        payloadToParams("{state:".toByteArray())
    }

    @Test
    fun configValidation() {
        val ok = MqttConfig(enabled = true, host = "broker.lan", name = "tv1")
        assertNull(ok.validate())
        assertNull(MqttConfig(enabled = false).validate())                            // off is always fine
        assertNotNull(MqttConfig(enabled = true, host = "", name = "tv1").validate())
        assertNotNull(ok.copy(host = "mqtt://broker.lan").validate())
        assertNotNull(ok.copy(host = "broker lan").validate())
        assertNotNull(ok.copy(port = 0).validate())
        assertNotNull(ok.copy(port = 70000).validate())
        assertNotNull(ok.copy(prefix = "home/pipup").validate())
        assertNotNull(ok.copy(name = "living room").validate())
        assertNotNull(ok.copy(name = "all").validate())                               // reserved
        assertNotNull(ok.copy(name = "tv#").validate())
    }

    @Test
    fun configUriAndRootNeverCarryTheSecret() {
        val cfg = MqttConfig(enabled = true, host = "broker.lan", port = 8883, tls = true,
            username = "u", password = "s3cret", prefix = "home", name = "tv1")
        assertEquals("ssl://broker.lan:8883", cfg.serverUri)
        assertEquals("home/tv1", cfg.root)
        assertTrue(!cfg.toString().contains("s3cret"))
    }

    @Test
    fun jsonAnswersArePublishedAsObjects() {
        val env = resultEnvelope(200, "application/json", """{"ok":true}""")
        assertEquals(200, env["status"])
        assertEquals(true, (env["body"] as com.fasterxml.jackson.databind.JsonNode).path("ok").asBoolean())
    }

    @Test
    fun textAnswersArePublishedAsStrings() {
        val env = resultEnvelope(400, "text/plain", "invalid request: no popup with id x")
        assertEquals(400, env["status"])
        assertEquals("invalid request: no popup with id x", env["body"])
    }

    @Test
    fun picturesAreBinary() {
        assertTrue(isBinaryContent("image/jpeg"))
        assertTrue(!isBinaryContent("application/json"))
        assertTrue(!isBinaryContent(null))
    }
}
