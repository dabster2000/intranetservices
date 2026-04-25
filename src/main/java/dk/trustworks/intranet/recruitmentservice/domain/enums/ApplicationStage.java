package dk.trustworks.intranet.recruitmentservice.domain.enums;

public enum ApplicationStage {
    SOURCED, CONTACTED, SCREENING,
    FIRST_INTERVIEW, CASE_OR_TECH_INTERVIEW, FINAL_INTERVIEW,
    OFFER, ACCEPTED, CONVERTED,
    REJECTED, WITHDRAWN, TALENT_POOL;

    public boolean isActive() {
        return switch (this) {
            case REJECTED, WITHDRAWN, TALENT_POOL, CONVERTED -> false;
            default -> true;
        };
    }

    public boolean isTerminal() {
        return this == CONVERTED || this == REJECTED || this == WITHDRAWN || this == TALENT_POOL;
    }
}
