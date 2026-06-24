/*
 * ============================================================================
 * Name        : BackupHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Narrow seam between DeployFragment and BackupController. The
 *               Fragment implements this so the controller (a non-Fragment) can
 *               reach the few cross-feature concerns it needs without owning the
 *               shared busy-state flags or the all-buttons refresh.
 * ============================================================================
 */
package org.iiab.controller.backup.presentation;

/** Cross-feature callbacks the BackupController needs from its host Fragment. */
public interface BackupHost {
    boolean isSystemBusy();
    String getSystemBusyMessage();
    void enableSystemProtection();
    void disableSystemProtection();
    String getTermuxArch();
    /** Re-evaluate enabled/alpha state of ALL deploy buttons (cross-feature). */
    void updateDynamicButtons();
    void setImporting(boolean importing);
    void setRestoring(boolean restoring);
    void setBackupInProgress(boolean inProgress);
    boolean isBackupInProgress();
}
