package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

import java.util.List;

/**
 * Link-queue candidate (AC8): a live cross-company INTERNAL/INTERNAL_SERVICE to an
 * in-scope debtor company with NO settlement stamp. Loose, read-only discovery —
 * a human links it; nothing is auto-matched. total normalized positive.
 */
public record UnlinkedInternalRow(String invoiceUuid, int invoicenumber, String type, String status,
                                  String issuerCompanyName, String debtorCompanyName, String invoicedate,
                                  String description, double total, List<String> itemNames) {}
