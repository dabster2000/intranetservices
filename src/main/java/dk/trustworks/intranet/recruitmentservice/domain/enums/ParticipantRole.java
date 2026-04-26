package dk.trustworks.intranet.recruitmentservice.domain.enums;

public enum ParticipantRole {
    LEAD_INTERVIEWER, SCORER, OBSERVER, TAM, PRACTICE_SUPPORT;

    public boolean canScore() { return this == LEAD_INTERVIEWER || this == SCORER; }
}
