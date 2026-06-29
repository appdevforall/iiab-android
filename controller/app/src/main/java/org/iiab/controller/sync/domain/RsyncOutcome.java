/*
 * ============================================================================
 * Name        : RsyncOutcome.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain mapping of rsync process exit codes to a typed result.
 *               Pure (no android.*, no I/O), so it is unit-testable on a plain
 *               JVM and clonable by an external host (Share-export, S14 step 1).
 *               Encodes the same exit-code policy previously inline in
 *               RsyncManager: 0/23/24 = ok, 10/12/20 = host dropped, else error.
 * ============================================================================
 */
package org.iiab.controller.sync.domain;

public final class RsyncOutcome {

    private RsyncOutcome() {
    }

    public enum Transfer {
        /** rsync finished (0 ok; 23 partial; 24 vanished source files). */
        COMPLETE,
        /** socket/stream drop — the peer went away (10/12/20). */
        HOST_DROPPED,
        /** any other non-zero exit. */
        ERROR
    }

    /** True for the exit codes treated as a successful transfer (0, 23, 24). */
    public static boolean isSuccess(int exitCode) {
        return exitCode == 0 || exitCode == 23 || exitCode == 24;
    }

    /** Classifies a (non-cancelled) transfer's exit code. */
    public static Transfer classifyTransfer(int exitCode) {
        if (isSuccess(exitCode)) return Transfer.COMPLETE;
        if (exitCode == 10 || exitCode == 12 || exitCode == 20) return Transfer.HOST_DROPPED;
        return Transfer.ERROR;
    }
}
