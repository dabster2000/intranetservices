package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/** Workbench roster entry (spec §4: "which accounts does the workbench cover"). */
public record SelfBilledSourceDTO(String clientUuid, String label, int accountNumber,
                                  String debtorCompanyUuid) {}
