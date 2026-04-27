package dk.trustworks.intranet.recruitmentservice.domain.integration;

public enum OutboxKind {
    OUTLOOK_EVENT_CREATE,
    OUTLOOK_EVENT_UPDATE,
    OUTLOOK_EVENT_CANCEL,
    SLACK_INTERVIEW_TOMORROW_DM,
    SLACK_SCORECARD_OVERDUE_DM
}
