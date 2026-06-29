package org.iiab.controller.feedback.domain;

/**
 * The rendered email for a {@link FeedbackPayload}: recipient, subject and plain-text
 * body. Pure value type produced by {@link FeedbackRenderer}; the presentation layer
 * turns it into an {@code ACTION_SENDTO}/{@code ACTION_SEND} intent.
 */
public final class EmailContent {

    private final String recipient;
    private final String subject;
    private final String body;

    public EmailContent(String recipient, String subject, String body) {
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
    }

    public String recipient() { return recipient; }

    public String subject() { return subject; }

    public String body() { return body; }
}
