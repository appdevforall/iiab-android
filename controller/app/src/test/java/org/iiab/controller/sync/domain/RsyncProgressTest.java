package org.iiab.controller.sync.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for {@link RsyncProgress} — parsing rsync --info=progress2 and
 * --stats output (S14 step 1). Pure JVM, no Android dependencies.
 */
public class RsyncProgressTest {

    @Test
    public void parsesProgressLine() {
        RsyncProgress p = RsyncProgress.parse("     32,768  45%  12.34MB/s    0:00:12");
        assertNotNull(p);
        assertEquals(45, p.percent);
        assertEquals("12.34MB/s", p.speed);
        assertEquals("0:00:12", p.eta);
    }

    @Test
    public void returnsNullWhenNoProgressToken() {
        assertNull(RsyncProgress.parse("sending incremental file list"));
        assertNull(RsyncProgress.parse(""));
        assertNull(RsyncProgress.parse(null));
    }

    @Test
    public void parsesTransferredBytesStrippingSeparators() {
        long b = RsyncProgress.parseTransferredBytes(
                "Total transferred file size: 1,234,567 bytes", -1L);
        assertEquals(1234567L, b);
    }

    @Test
    public void returnsFallbackWhenStatsLineAbsent() {
        assertEquals(99L, RsyncProgress.parseTransferredBytes("some other line", 99L));
        assertEquals(0L, RsyncProgress.parseTransferredBytes(null, 0L));
    }
}
