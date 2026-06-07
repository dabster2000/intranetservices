package dk.trustworks.intranet.aggregates.invoice.dto;

import java.util.Set;

/**
 * Request body for the human-initiated settle endpoint (Decision D4). Identifies
 * one settlement group by its {@link SettlementGroupKey} components, the issuer
 * companies to settle (issuer-grain partial settle), and whether the emitted
 * documents should stop at QUEUED ({@code queue=true}) or be force-finalized.
 */
public record SettlementSettleRequest(String billingClientUuid, String debtorCompanyUuid,
                                      int year, int month, Set<String> issuerCompanyUuids, boolean queue) {}
