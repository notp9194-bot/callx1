package com.callx.app.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.callx.app.models.Message;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class LastMessagesCacheTest {

    private static Message msg(String id, long ts) {
        Message m = new Message();
        m.id = id;
        m.messageId = id;
        m.timestamp = ts;
        return m;
    }

    @Before public void reset() { LastMessagesCache.getInstance().clear(); }

    @Test public void deletedAndExpiredRows_neverShowInWarmFrame() {
        long now = System.currentTimeMillis();
        Message ok = msg("ok", 1);
        Message deleted = msg("del", 2);
        deleted.deleted = true;
        Message expired = msg("exp", 3);
        expired.expiresAt = now - 1000L;
        Message future = msg("fut", 4);
        future.expiresAt = now + 60_000L;
        List<Message> in = new ArrayList<>();
        in.add(ok); in.add(deleted); in.add(expired); in.add(future);
        LastMessagesCache.getInstance().seed("c", in);
        List<Message> out = LastMessagesCache.getInstance().get("c");
        assertEquals(2, out.size());
        assertEquals("ok", out.get(0).id);
        assertEquals("fut", out.get(1).id);
    }

    @Test public void upsertOfDeletedMessage_removesTheVisibleCopy() {
        LastMessagesCache c = LastMessagesCache.getInstance();
        c.upsert("c", msg("a", 1));
        c.upsert("c", msg("b", 2));
        Message d = msg("a", 1);
        d.deleted = true;
        c.upsert("c", d);
        List<Message> out = c.get("c");
        assertEquals(1, out.size());
        assertEquals("b", out.get(0).id);
    }

    @Test public void has_isFalseWhenEverythingIsHidden() {
        Message d = msg("a", 1);
        d.deleted = true;
        List<Message> in = new ArrayList<>();
        in.add(d);
        LastMessagesCache.getInstance().seed("c", in);
        assertFalse(LastMessagesCache.getInstance().has("c"));
    }

    @Test public void age_isUnknownUntilSeeded_thenSmall_andLegacyStampIsUnknown() {
        LastMessagesCache c = LastMessagesCache.getInstance();
        assertEquals(Long.MAX_VALUE, c.getAgeMs("c"));
        List<Message> in = new ArrayList<>();
        in.add(msg("a", 1));
        c.seed("c", in);
        assertTrue(c.getAgeMs("c") < 5_000L);
        c.seed("c", in, 0L);                       // migrated legacy snapshot: age unknown
        assertEquals(Long.MAX_VALUE, c.getAgeMs("c"));
        c.seed("c", in, System.currentTimeMillis() - 10_000L);
        assertTrue(c.getAgeMs("c") >= 10_000L);
    }

    @Test public void clearAndEvict_dropAge() {
        LastMessagesCache c = LastMessagesCache.getInstance();
        List<Message> in = new ArrayList<>();
        in.add(msg("a", 1));
        c.seed("c", in);
        c.evictChat("c");
        assertEquals(Long.MAX_VALUE, c.getAgeMs("c"));
    }
}
