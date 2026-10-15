package com.callx.app.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StatusItemExpiryTest {
    private static final long H = 3_600_000L;
    private static final long NOW = 1_000_000_000_000L;

    private static StatusItem item(Long ts, Long exp, Boolean deleted) {
        StatusItem s = new StatusItem();
        s.timestamp = ts; s.expiresAt = exp; s.deleted = deleted;
        return s;
    }

    @Test public void explicitFutureExpiry_isLive() {
        assertTrue(item(NOW - H, NOW + H, null).isLive(NOW));
    }

    @Test public void explicitPastExpiry_isDead() {
        assertFalse(item(NOW - 30 * H, NOW - H, null).isLive(NOW));
    }

    @Test public void nullExpiry_fallsBackTo24hFromTimestamp() {
        assertTrue(item(NOW - 23 * H, null, null).isLive(NOW));
        assertFalse(item(NOW - 25 * H, null, null).isLive(NOW));   // the original bug: used to be "live forever"
    }

    @Test public void zeroExpiry_treatedAsMissing() {
        assertFalse(item(NOW - 25 * H, 0L, null).isLive(NOW));
        assertTrue(item(NOW - H, 0L, null).isLive(NOW));
    }

    @Test public void noExpiryAndNoTimestamp_failsClosed() {
        assertFalse(item(null, null, null).isLive(NOW));
    }

    @Test public void deleted_isDeadEvenIfNotExpired() {
        assertFalse(item(NOW - H, NOW + H, Boolean.TRUE).isLive(NOW));
        assertTrue(item(NOW - H, NOW + H, Boolean.FALSE).isLive(NOW));
    }

    @Test public void expiryBoundary_isExclusive() {
        assertFalse(item(NOW - H, NOW, null).isLive(NOW));
        assertTrue(item(NOW - H, NOW + 1, null).isLive(NOW));
    }

    @Test public void staticHelpers_matchInstance() {
        assertEquals(NOW + H, StatusItem.effectiveExpiry(NOW + H, NOW - H));
        assertEquals(NOW - H + 24 * H, StatusItem.effectiveExpiry(null, NOW - H));
        assertEquals(0L, StatusItem.effectiveExpiry(null, null));
        assertFalse(StatusItem.isLive(null, NOW));
    }
}
