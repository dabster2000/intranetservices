package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/**
 * Assign-modal consequence preview (spec §5.3): ties the caller-selected
 * consultant + work period (which may differ from the suggestion) to its
 * cross-company verdict, the issuer/debtor companies, and the registered-work
 * CROSS-CHECK value (never a settlement basis — AC1). issuerCompanyUuid/Name are
 * null when the consultant's company can't be resolved for the period; workValue
 * is normalized positive and 0.0 when no work is registered.
 */
public record AssignContextDTO(boolean crossCompany,
                               String issuerCompanyUuid, String issuerCompanyName,
                               String debtorCompanyUuid, String debtorCompanyName,
                               double workValue) {}
