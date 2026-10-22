package com.callx.app.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.callx.app.db.entity.MessageEntity;

import org.junit.Test;

public class MessageMergePolicyTest {

    private static MessageEntity row(String id) {
        MessageEntity e = new MessageEntity();
        e.id = id;
        return e;
    }

    @Test public void newMessage_isWrittenAsIs() {
        MessageEntity in = row("a");
        in.text = "hi";
        assertSame(in, MessageMergePolicy.merge(null, in));
    }

    @Test public void localDeleteForMe_tombstone_ignoresStaleSnapshot() {
        MessageEntity local = row("a");
        local.deleted = true;
        MessageEntity in = row("a");
        in.text = "resurrected?";
        assertNull(MessageMergePolicy.merge(local, in));
    }

    @Test public void incomingDelete_wipesContent() {
        MessageEntity local = row("a");
        local.text = "secret";
        MessageEntity in = row("a");
        in.deleted = true;
        in.text = "secret";
        in.mediaUrl = "https://x/y.jpg";
        MessageEntity out = MessageMergePolicy.merge(local, in);
        assertEquals("", out.text);
        assertNull(out.mediaUrl);
    }

    @Test public void newerLocalEdit_beatsOlderServerCopy() {
        MessageEntity local = row("a");
        local.text = "edited locally";
        local.editedAt = 200L;
        MessageEntity in = row("a");
        in.text = "older server text";
        in.editedAt = 100L;
        MessageEntity out = MessageMergePolicy.merge(local, in);
        assertEquals("edited locally", out.text);
        assertEquals(Long.valueOf(200L), out.editedAt);
    }

    @Test public void newerServerEdit_winsOverLocal() {
        MessageEntity local = row("a");
        local.text = "old";
        local.editedAt = 100L;
        MessageEntity in = row("a");
        in.text = "newer";
        in.editedAt = 200L;
        assertEquals("newer", MessageMergePolicy.merge(local, in).text);
    }

    @Test public void statusNeverRegresses() {
        MessageEntity local = row("a");
        local.status = "read";
        local.readAt = 50L;
        MessageEntity in = row("a");
        in.status = "sent";
        MessageEntity out = MessageMergePolicy.merge(local, in);
        assertEquals("read", out.status);
        assertEquals(Long.valueOf(50L), out.readAt);
    }

    @Test public void statusRank_order() {
        assertTrue(MessageMergePolicy.statusRank("read") > MessageMergePolicy.statusRank("delivered"));
        assertTrue(MessageMergePolicy.statusRank("delivered") > MessageMergePolicy.statusRank("sent"));
        assertTrue(MessageMergePolicy.statusRank("sent") > MessageMergePolicy.statusRank("failed"));
        assertTrue(MessageMergePolicy.statusRank("failed") > MessageMergePolicy.statusRank(null));
    }

    @Test public void localFilePath_andWireText_areKept() {
        MessageEntity local = row("a");
        local.mediaLocalPath = "/data/x.jpg";
        local.wireText = "CIPHERTEXT";
        MessageEntity in = row("a");
        MessageEntity out = MessageMergePolicy.merge(local, in);
        assertEquals("/data/x.jpg", out.mediaLocalPath);
        assertEquals("CIPHERTEXT", out.wireText);
    }

    @Test public void deviceLocalColumns_areNullFilled() {
        MessageEntity local = row("a");
        local.starred = true;
        local.pinned = true;
        local.viewOnceState = "opened";
        local.editHistoryJson = "[{\"t\":\"v1\"}]";
        MessageEntity in = row("a");
        MessageEntity out = MessageMergePolicy.merge(local, in);
        assertEquals(Boolean.TRUE, out.starred);
        assertEquals(Boolean.TRUE, out.pinned);
        assertEquals("opened", out.viewOnceState);
        assertEquals("[{\"t\":\"v1\"}]", out.editHistoryJson);
    }

    @Test public void incomingValue_overridesLocalOnlyColumn() {
        MessageEntity local = row("a");
        local.starred = true;
        MessageEntity in = row("a");
        in.starred = false;
        assertEquals(Boolean.FALSE, MessageMergePolicy.merge(local, in).starred);
    }

    @Test public void serverDrivenReactions_areNotNullFilled() {
        MessageEntity local = row("a");
        local.reactionsJson = "{\"u1\":\"👍\"}";
        MessageEntity in = row("a");   // reaction was removed on the server -> null
        assertNull(MessageMergePolicy.merge(local, in).reactionsJson);
    }
}
