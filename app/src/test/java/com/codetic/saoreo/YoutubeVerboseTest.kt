package com.codetic.saoreo

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeVerboseTest {
    @Test fun retainsRetryCountAndStatusWithoutSiteText() {
        val line = "WARNING: [youtube] AbCdeFGHiJ0: HTTP Error 429. Retrying (1/3): https://example.test/?token=secret"
        assertEquals("verbose.warning.retry status=429 retry=1/3", YoutubeVerbose.summary(line))
    }

    @Test fun retainsRecognizedClientWithoutVideoIdentifier() {
        val line = "[youtube] AbCdeFGHiJ0: Downloading tv samsung player API JSON"
        assertEquals("verbose.info.player_api.client_tv_samsung", YoutubeVerbose.summary(line))
    }

    @Test fun unknownTextIsRedacted() {
        assertEquals("verbose.debug.redacted",
            YoutubeVerbose.summary("[debug] Cookie: session=private; PO=secret /data/local/dir"))
    }
}
