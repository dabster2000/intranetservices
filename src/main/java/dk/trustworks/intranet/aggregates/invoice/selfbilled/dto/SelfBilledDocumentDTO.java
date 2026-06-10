package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

import java.util.List;

/**
 * One self-billing DOCUMENT = one e-conomic voucher, netted (D10). lineUuid is the
 * anchor line (the parseable Faktura line, or the first entry). amount normalized
 * positive. suggested* are machine suggestions (authority = assignments).
 */
public record SelfBilledDocumentDTO(String lineUuid, int voucherNumber, String bookingDate,
                                    double amount, String sourceText, String fakturaNumber,
                                    String suggestedCode, Integer suggestedWorkYear, Integer suggestedWorkMonth,
                                    String suggestedConsultantUuid, String suggestedConsultantName,
                                    int suggestionConfidence, String suggestionReason,
                                    String status, boolean crossCompany, int entryCount,
                                    List<SelfBilledAssignmentDTO> assignments) {}
