package dk.trustworks.intranet.recruitmentservice.application.integration;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Canonical formats for outbox {@code idempotency_key} strings. Centralised so
 * producers (e.g., domain event handlers, scheduled scanners) and consumers
 * (the worker / handlers, in tests) agree on byte-exact key shapes — the unique
 * constraint on {@code recruitment_external_outbox.idempotency_key} is what
 * gives the system its at-most-once dispatch guarantee.
 *
 * <p>Spec source: Recruitment Slice 3b plan, Phase F idempotency table.
 */
public final class OutboxIdempotencyKeys {

    private OutboxIdempotencyKeys() {}

    /** Outlook event create — one per interview lifecycle. */
    public static String outlookCreate(String interviewUuid) {
        return "interview:" + requireNotBlank(interviewUuid, "interviewUuid");
    }

    /**
     * Outlook event update — keyed per reschedule version so successive
     * reschedules of the same interview each produce a distinct outbox row.
     */
    public static String outlookUpdate(String interviewUuid, int rescheduleVersion) {
        if (rescheduleVersion < 1) {
            throw new IllegalArgumentException("rescheduleVersion must be >= 1, got " + rescheduleVersion);
        }
        return "interview:" + requireNotBlank(interviewUuid, "interviewUuid") + ":v" + rescheduleVersion;
    }

    /** Outlook event cancel — at most one per interview. */
    public static String outlookCancel(String interviewUuid) {
        return "interview:" + requireNotBlank(interviewUuid, "interviewUuid") + ":cancel";
    }

    /**
     * Slack interview-tomorrow DM — keyed per interview, per recipient, per
     * (Europe/Copenhagen) target date so the same interview won't double-send
     * if rescheduled into a different day.
     */
    public static String slackInterviewTomorrow(String interviewUuid, String recipientUserUuid, LocalDate targetDate) {
        return "tomorrow:"
                + requireNotBlank(interviewUuid, "interviewUuid") + ":"
                + requireNotBlank(recipientUserUuid, "recipientUserUuid") + ":"
                + Objects.requireNonNull(targetDate, "targetDate");
    }

    /**
     * Slack scorecard-overdue DM — keyed per interview, per scorer, per
     * escalation stage (1/2/3) so each stage can trigger exactly once.
     */
    public static String slackScorecardOverdue(String interviewUuid, String scorerUuid, int stage) {
        if (stage < 1) {
            throw new IllegalArgumentException("stage must be >= 1, got " + stage);
        }
        return "overdue:"
                + requireNotBlank(interviewUuid, "interviewUuid") + ":"
                + requireNotBlank(scorerUuid, "scorerUuid")
                + ":stage" + stage;
    }

    private static String requireNotBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        return value;
    }
}
