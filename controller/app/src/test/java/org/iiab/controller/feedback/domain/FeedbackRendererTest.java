package org.iiab.controller.feedback.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedbackRendererTest {

    private final FeedbackRenderer renderer = new FeedbackRenderer();

    private static FeedbackPayload.Builder base(FeedbackType type) {
        return FeedbackPayload.builder(type)
                .appVersion("v0.4.0-beta")
                .appBuild(55)
                .androidRelease("14")
                .device("Google Pixel 7")
                .abi("arm64-v8a");
    }

    @Test
    public void recipientUsesPlusAddressing() {
        EmailContent c = renderer.render(base(FeedbackType.BUG).build());
        assertEquals("feedback+k2go@appdevforall.org", c.recipient());
    }

    @Test
    public void crashSubjectCarriesVersionAndBodyHasStackTrace() {
        EmailContent c = renderer.render(
                base(FeedbackType.CRASH).stackTrace("java.lang.NullPointerException").build());
        assertEquals("[K2Go] Crash \u2014 v0.4.0-beta (55)", c.subject());
        assertTrue(c.body().contains("--- Stack trace ---"));
    }

    @Test
    public void feedbackSubjectCarriesScreenAndHasNoStackTrace() {
        EmailContent c = renderer.render(base(FeedbackType.BUG).screen("Install").build());
        assertEquals("[K2Go] Bug \u2014 Install", c.subject());
        assertFalse(c.body().contains("--- Stack trace ---"));
    }

    @Test
    public void contentRequestHasNoSuffixWhenNoScreen() {
        EmailContent c = renderer.render(base(FeedbackType.CONTENT_REQUEST).build());
        assertEquals("[K2Go] Content request", c.subject());
    }

    @Test
    public void bodyHasProductTagAndRequiredFields() {
        EmailContent c = renderer.render(base(FeedbackType.SUGGESTION).message("nice app").build());
        String body = c.body();
        assertTrue(body.contains("Product: K2Go"));
        assertTrue(body.contains("Type: Suggestion"));
        assertTrue(body.contains("App version: v0.4.0-beta (build 55)"));
        assertTrue(body.contains("Android: 14"));
        assertTrue(body.contains("Device: Google Pixel 7"));
        assertTrue(body.contains("ABI: arm64-v8a"));
        assertTrue(body.contains("nice app"));
    }

    @Test
    public void optionalFieldsOmittedWhenAbsent() {
        EmailContent c = renderer.render(base(FeedbackType.BUG).build());
        assertFalse(c.body().contains("Rootfs build:"));
        assertFalse(c.body().contains("Rootfs checksum:"));
        assertFalse(c.body().contains("Contact:"));
    }

    @Test
    public void rootfsAndBinariesRenderedWhenPresent() {
        EmailContent c = renderer.render(base(FeedbackType.BUG)
                .rootfsBuild("iiab-arm64-medium-2026-06-20")
                .rootfsChecksum(RootfsChecksumStatus.VERIFIED)
                .binariesTag("binaries-2026-06-26_10-25")
                .build());
        String body = c.body();
        assertTrue(body.contains("Rootfs build: iiab-arm64-medium-2026-06-20"));
        assertTrue(body.contains("Rootfs checksum: sha256 verified"));
        assertTrue(body.contains("Binaries: binaries-2026-06-26_10-25"));
    }
}
