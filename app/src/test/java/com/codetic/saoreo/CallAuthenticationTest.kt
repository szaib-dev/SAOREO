package com.codetic.saoreo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class CallAuthenticationTest {
    private fun keys() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    @Test fun bothDevicesMustApproveBeforeSdpCanPass() {
        val secret = callPairingKey(newCallPairingSecret())
        val a = CallAuthentication(keys(), "room", "a", "b", secret)
        val b = CallAuthentication(keys(), "room", "b", "a", secret)
        assertTrue(a.receiveHello(b.hello))
        assertTrue(b.receiveHello(a.hello))
        a.confirm()
        assertTrue(b.receiveApproval(a.approval()!!))
        assertFalse(b.ready)
        assertFalse(a.ready)
        b.confirm()
        assertTrue(a.receiveApproval(b.approval()!!))
        assertTrue(a.ready && b.ready)
        val offer = JSONObject().put("type", "offer").put("sdp", "phone A fingerprint")
        val envelope = a.seal(offer)
        val modified = JSONObject(envelope.toString()).put("body", offer.put("sdp", "VPS fingerprint").toString())
        assertNull(b.open(modified))
        assertEquals("phone A fingerprint", b.open(envelope)?.getString("sdp"))
        assertNull(b.open(envelope))
    }

    @Test fun relayCannotSubstituteAKeyOrReplayAnOldApproval() {
        val aKeys = keys()
        val bKeys = keys()
        val secret = callPairingKey(newCallPairingSecret())
        val a = CallAuthentication(aKeys, "room", "a", "b", secret)
        val b = CallAuthentication(bKeys, "room", "b", "a", secret)
        val impostor = CallAuthentication(keys(), "room", "b", "a")
        assertFalse(a.receiveHello(impostor.hello))
        assertFalse(a.receiveHello(CallAuthentication(keys(), "room", "b", "a", callPairingKey(newCallPairingSecret())).hello))
        assertTrue(b.receiveHello(a.hello))
        assertTrue(a.receiveHello(b.hello))
        assertFalse(a.ready)

        val freshA = CallAuthentication(aKeys, "room", "a", "b", secret)
        assertTrue(freshA.receiveHello(b.hello))
        b.confirm()
        freshA.confirm()
        assertFalse(freshA.receiveApproval(b.approval()!!))
        assertFalse(freshA.ready)
        assertFalse(freshA.receiveHello(CallAuthentication(bKeys, "another-room", "b", "a", secret).hello))
    }
}
