package dk.trustworks.intranet.recruitmentservice.domain.enums;

public enum AiArtifactState {
    NOT_GENERATED,
    GENERATING,
    GENERATED,
    REVIEWED,
    OVERRIDDEN,
    FAILED;

    public boolean isInProgress() { return this == GENERATING; }
    public boolean isReviewable() { return this == GENERATED; }
    public boolean isTerminal()   { return this == REVIEWED || this == OVERRIDDEN || this == FAILED; }
}
