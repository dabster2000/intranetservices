package dk.trustworks.intranet.recruitmentservice.domain.enums;

public enum InterviewStatus {
    SCHEDULED, HELD, CANCELLED, ROUNDED_UP, RESCHEDULED;

    public boolean isTerminal() { return this == ROUNDED_UP; }
    public boolean isActive()   { return this == SCHEDULED || this == HELD; }
}
