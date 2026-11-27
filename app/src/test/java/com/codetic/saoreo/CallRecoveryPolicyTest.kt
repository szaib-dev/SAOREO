package com.codetic.saoreo

import org.junit.Assert.assertEquals
import org.junit.Test

class CallRecoveryPolicyTest {
    @Test fun networkLossWaitsAndRestoredSignalingRestartsIce() {
        assertEquals(CallRecoveryAction.WAIT, CallRecoveryPolicy.action(true, false, false))
        assertEquals(CallRecoveryAction.RESTART, CallRecoveryPolicy.action(true, false, true))
        assertEquals(CallRecoveryAction.CONNECTED, CallRecoveryPolicy.action(true, true, false))
    }

    @Test fun retriesStayBoundedAndUserHangupWinsEvenAfterNetworkReturns() {
        assertEquals(3500L, CallRecoveryPolicy.delay(0))
        assertEquals(15000L, CallRecoveryPolicy.delay(100))
        assertEquals(CallRecoveryAction.STOP, CallRecoveryPolicy.action(false, false, true))
    }
}
