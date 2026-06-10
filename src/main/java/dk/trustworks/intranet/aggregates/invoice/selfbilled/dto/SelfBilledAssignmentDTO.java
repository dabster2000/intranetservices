package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/** One assignment as shown in the worklist. shareAmount normalized positive. consultantName null when masked. */
public record SelfBilledAssignmentDTO(String uuid, String consultantUuid, String consultantName,
                                      int workYear, int workMonth, double shareAmount,
                                      String source, String assignedBy, String assignedAt) {}
