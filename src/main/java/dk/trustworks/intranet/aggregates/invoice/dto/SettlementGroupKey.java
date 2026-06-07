package dk.trustworks.intranet.aggregates.invoice.dto;

/**
 * Identity of a phantom settlement group (Decision D2): one external client
 * relationship, one receiving (debtor) Trustworks company, one calendar month.
 * All of a month's phantoms for the same mapped client + company share this key.
 *
 * <p>Pure value type — no DB, no CDI. Records give value-based equals/hashCode so
 * it can be used directly as a map key when grouping phantoms.
 */
public record SettlementGroupKey(String billingClientUuid, String debtorCompanyUuid, int year, int month) {

    /**
     * @return a key, or {@code null} when the client or company is missing
     *         (an unmapped phantom cannot form a settlement group — it surfaces
     *         via the review queue / "Needs mapping" instead).
     */
    public static SettlementGroupKey from(String billingClientUuid, String debtorCompanyUuid, int year, int month) {
        if (billingClientUuid == null || billingClientUuid.isBlank()) return null;
        if (debtorCompanyUuid == null || debtorCompanyUuid.isBlank()) return null;
        return new SettlementGroupKey(billingClientUuid.trim(), debtorCompanyUuid.trim(), year, month);
    }

    /** Stable string form for logging and string-keyed maps. */
    public String asString() {
        return billingClientUuid + "|" + debtorCompanyUuid + "|" + year + "|" + month;
    }
}
