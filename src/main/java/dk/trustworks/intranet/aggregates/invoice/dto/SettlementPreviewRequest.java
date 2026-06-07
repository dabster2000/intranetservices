package dk.trustworks.intranet.aggregates.invoice.dto;

import java.util.Set;

/**
 * Request body for the settlement-group preview endpoint (read-only). Identifies
 * one settlement group by its {@link SettlementGroupKey} components and optionally
 * carries the set of attribution UUIDs the caller has deselected in the Settle
 * dialog (per-consultant exclusion is plumbed through preview only — see master
 * plan "Deferred / non-goals").
 */
public record SettlementPreviewRequest(String billingClientUuid, String debtorCompanyUuid,
                                       int year, int month, Set<String> excludedAttributionUuids) {}
