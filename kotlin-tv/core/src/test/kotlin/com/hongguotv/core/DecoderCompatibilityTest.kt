// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class DecoderCompatibilityTest {
    private fun state(mode: DecoderMode = DecoderMode.AUTO) = DecoderRecovery().apply { begin("series:episode", mode) }

    @Test fun startsWithRequestedMode() { assertEquals(DecoderMode.COMPATIBLE, state(DecoderMode.COMPATIBLE).mode) }
    @Test fun autoHasExactlyTwoFallbacks() {
        val s = state()
        assertEquals(DecoderMode.COMPATIBLE, s.advance(4003))
        assertEquals(DecoderMode.SOFTWARE, s.advance(4003))
        repeat(10) { assertNull(s.advance(4003)) }
    }
    @Test fun initializationFailureCanRecover() { assertEquals(DecoderMode.COMPATIBLE, state().next(4001)) }
    @Test fun missingVideoTrackCanRecover() { assertEquals(DecoderMode.COMPATIBLE, state().next(4005)) }
    @Test fun reclaimedResourcesCanRecover() { assertEquals(DecoderMode.COMPATIBLE, state().next(4006)) }
    @Test fun audioInitializationCanRecover() { assertEquals(DecoderMode.COMPATIBLE, state().next(5001)) }
    @Test fun audioWriteFailureCanRecover() { assertEquals(DecoderMode.COMPATIBLE, state().next(5002)) }
    @Test fun networkFailureDoesNotConsumeDecoderBudget() {
        val s = state()
        for (code in listOf(1003, 2000, 2001, 2002, 2004, 2006)) assertNull(s.advance(code))
        assertEquals(DecoderMode.AUTO, s.mode)
    }
    @Test fun parsingFailureDoesNotTriggerPointlessSoftwareRetry() {
        val s = state()
        for (code in 3001..3004) assertNull(s.advance(code))
        assertEquals(DecoderMode.AUTO, s.mode)
    }
    @Test fun drmAndUnknownErrorsAreNotDecoderErrors() {
        for (code in listOf(0, 1000, 4000, 4999, 5003, 6001)) assertNull(state().next(code))
    }
    @Test fun repeatedPrepareCannotResetBudget() {
        val s = state(); s.advance(4003)
        s.begin("series:episode", DecoderMode.AUTO)
        assertEquals(DecoderMode.SOFTWARE, s.advance(4003))
        s.begin("series:episode", DecoderMode.AUTO)
        assertNull(s.advance(4003))
    }
    @Test fun newEpisodeGetsFreshBudget() {
        val s = state(); s.advance(4003); s.advance(4003)
        s.begin("series:next", DecoderMode.AUTO)
        assertEquals(DecoderMode.AUTO, s.mode)
    }
    @Test fun explicitPreferenceResetsMode() {
        val s = state(); s.advance(4003)
        s.reset(DecoderMode.SOFTWARE); s.begin("series:episode", DecoderMode.SOFTWARE)
        assertEquals(DecoderMode.SOFTWARE, s.mode); assertNull(s.next(4003))
    }
    @Test fun changedPreferenceRebuildsMode() {
        val s = state(); s.advance(4003)
        s.begin("series:episode", DecoderMode.SOFTWARE)
        assertEquals(DecoderMode.SOFTWARE, s.mode)
    }
    @Test fun qualityCapsNeverRaiseUserChoice() {
        assertEquals(1080, DecoderMode.AUTO.qualityLimit(1080))
        assertEquals(720, DecoderMode.COMPATIBLE.qualityLimit(1080))
        assertEquals(360, DecoderMode.COMPATIBLE.qualityLimit(360))
        assertEquals(480, DecoderMode.SOFTWARE.qualityLimit(1080))
        assertEquals(360, DecoderMode.SOFTWARE.qualityLimit(360))
    }
    @Test fun avcMetadataVariantsRankFirst() {
        for (codec in listOf("h264", "H.264", "AVC", "avc1.640028", "avc3", "video/avc")) assertEquals(codec, 0, StreamCodecPreference.rank(codec))
    }
    @Test fun unknownMetadataRemainsUsable() { assertEquals(1, StreamCodecPreference.rank("")); assertEquals(1, StreamCodecPreference.rank("  ")) }
    @Test fun hevcMetadataRanksAfterAvc() {
        for (codec in listOf("h265", "H.265", "HEVC", "bytevc1", "hvc1.1.6", "hev1", "video/hevc")) assertEquals(codec, 2, StreamCodecPreference.rank(codec))
    }
    @Test fun unsupportedMetadataDoesNotBecomeAvc() { assertEquals(3, StreamCodecPreference.rank("bytevc2")) }
    @Test fun containerErrorsAreNotMislabelledAsDecoding() {
        assertTrue(DecoderRecovery.isContainerError(3001)); assertTrue(DecoderRecovery.isContainerError(3002))
        assertFalse(DecoderRecovery.isDecoderError(3001)); assertFalse(DecoderRecovery.isDecoderError(3002))
        assertTrue(DecoderRecovery.isDecoderError(4003)); assertFalse(DecoderRecovery.isContainerError(4003))
    }
    @Test fun invalidSavedPreferenceHasSafeDefault() { assertEquals(DecoderMode.AUTO, DecoderMode.parse(null)); assertEquals(DecoderMode.AUTO, DecoderMode.parse("invalid")) }
}
