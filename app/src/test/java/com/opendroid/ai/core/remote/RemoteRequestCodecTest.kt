package com.opendroid.ai.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRequestCodecTest {

    private val request = SignedRemoteRequest(
        keyId = "key-1",
        deviceId = "target-1",
        action = RemoteAction("battery_status", "{}", RemoteRisk.LOW),
        timestampMs = 1234567890L,
        nonce = "nonce-1",
        signatureHex = "deadbeef",
    )

    @Test
    fun `encode then decode returns the same request`() {
        val decoded = RemoteRequestCodec.decode(RemoteRequestCodec.encode(request))

        assertEquals(request, decoded)
    }

    @Test
    fun `the signed payload is unchanged by a round trip`() {
        val decoded = RemoteRequestCodec.decode(RemoteRequestCodec.encode(request))!!

        assertEquals(request.canonicalPayload(), decoded.canonicalPayload())
    }

    @Test
    fun `arguments containing newlines and quotes survive the round trip`() {
        val tricky = request.copy(
            action = request.action.copy(argumentsJson = "{\"text\":\"a\\nb\",\"q\":\"he said \\\"hi\\\"\"}")
        )

        val decoded = RemoteRequestCodec.decode(RemoteRequestCodec.encode(tricky))!!

        assertEquals(tricky.action.argumentsJson, decoded.action.argumentsJson)
        assertEquals(tricky.canonicalPayload(), decoded.canonicalPayload())
    }

    @Test
    fun `incomplete or malformed lines decode to null rather than throwing`() {
        assertNull(RemoteRequestCodec.decode(""))
        assertNull(RemoteRequestCodec.decode("not json"))
        assertNull(RemoteRequestCodec.decode("""{"keyId":"k"}"""))
        assertNull(RemoteRequestCodec.decode("""{"keyId":"k","risk":"NOPE"}"""))
    }

    @Test
    fun `reply round trips and malformed replies decode to null`() {
        val reply = RemoteTransportReply(true, "battery_status", "Battery: 80%, discharging.")

        assertEquals(reply, RemoteTransportReply.decode(reply.encode()))
        assertNull(RemoteTransportReply.decode("{}"))
        assertNull(RemoteTransportReply.decode("nonsense"))
    }

    @Test
    fun `a decoded request is rejected by the service when its signature does not match`() {
        val service = RemoteControlService(keyStore = InMemoryDeviceKeyStore(), now = { 5_000L })
        val session = service.startPairing("target-1", "Phone")
        service.completePairing(session.code, "key-1")
        val signed = service.sign("key-1", "target-1", request.action, 5_000L, "n1")
        val tampered = RemoteRequestCodec.decode(RemoteRequestCodec.encode(signed.copy(signatureHex = "00")))!!

        val result = service.authenticate(tampered)

        assertTrue(result is RemoteAuthentication.Rejected)
        assertEquals(RemoteControlService.BAD_SIGNATURE, (result as RemoteAuthentication.Rejected).reason)
    }
}
