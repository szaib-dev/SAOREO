package com.codetic.saoreo

import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PrivacyControlsTest {
    private val app get() = ApplicationProvider.getApplicationContext<SaoreoApp>()

    @Test fun captureProtectionUpdatesExistingAndRecreatedWindows() {
        val policy = app.screenCaptureProtection
        val previous = policy.enabled.value
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    policy.setEnabled(false)
                    assertEquals(0, activity.window.attributes.flags and FLAG_SECURE)
                    policy.setEnabled(true)
                    assertEquals(FLAG_SECURE, activity.window.attributes.flags and FLAG_SECURE)
                }
                scenario.recreate().onActivity { activity ->
                    assertEquals(FLAG_SECURE, activity.window.attributes.flags and FLAG_SECURE)
                    policy.setEnabled(false)
                    assertEquals(0, activity.window.attributes.flags and FLAG_SECURE)
                }
            } finally { scenario.onActivity { policy.setEnabled(previous) } }
        }
    }

    @Test fun keystoreIdentitySignsAndPinsOnePairedDeviceAcrossInstances() {
        val first = "test-call-a-${UUID.randomUUID()}"
        val second = "test-call-b-${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val identity = CallIdentity(app, first)
        try {
            val secret = newCallPairingSecret()
            assertTrue(identity.startPairing(secret))
            val a = requireNotNull(identity.exchange("room", "a", "b"))
            val b = requireNotNull(CallIdentity(app, second).exchange("room", "b", "a", secret))
            assertNull(identity.pairedFingerprint())
            assertTrue(a.receiveHello(b.hello))
            assertTrue(b.receiveHello(a.hello))
            assertTrue(identity.rememberPaired(a))
            assertEquals(a.remoteFingerprint, CallIdentity(app, first).pairedFingerprint())
            a.confirm()
            b.confirm()
            assertTrue(a.receiveApproval(b.approval()!!))
            assertTrue(b.receiveApproval(a.approval()!!))
            assertEquals("candidate", b.open(a.seal(JSONObject().put("type", "candidate")))?.optString("type"))
        } finally {
            identity.forgetAll()
            store.deleteEntry(first)
            store.deleteEntry(second)
        }
    }

    @Test fun doNotDisturbSuppressesIncomingCallAlerts() {
        val expected = InstrumentationRegistry.getArguments().getString("together_dnd")
        assumeTrue("Run with the emulator's Do Not Disturb set to the requested state", expected != null)
        assertEquals(expected == "off", togetherCallAlertsAllowed(app))
    }
}
