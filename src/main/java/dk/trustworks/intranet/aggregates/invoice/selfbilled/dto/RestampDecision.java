package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/** What the re-stamp planner decided for one existing internal (carries the stamp target). */
public record RestampDecision(String internalUuid, Outcome outcome, String clientUuid, String debtorCompanyUuid,
                              int workYear, int workMonth, String detail) {
    public enum Outcome { RESTAMP, NO_CHANGE, UNMATCHED, AMBIGUOUS }
}
