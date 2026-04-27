package dk.trustworks.intranet.recruitmentservice.domain.integration;

public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    DONE,
    FAILED
}
