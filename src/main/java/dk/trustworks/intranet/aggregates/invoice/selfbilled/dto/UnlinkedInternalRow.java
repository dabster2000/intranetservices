package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

import java.util.List;

/**
 * Link-queue candidate (AC8): a live cross-company INTERNAL/INTERNAL_SERVICE to an
 * in-scope debtor company with NO settlement stamp, narrowed to internals that are
 * self-billed-related (no source ref, missing source row, or a source whose client is
 * an enabled self-billed source). Loose, read-only discovery — a human links it;
 * nothing is auto-matched. total normalized positive.
 *
 * <p>Prefill (Feature 2b): {@code suggestedConsultant*} come from the internal's own
 * items when they all carry the SAME consultant (else null); {@code suggestedWork*}
 * come from the referenced source invoice's period when the source exists (else null).
 * The suggested consultant uuid+name are masked without {@code users:read}; periods stay.
 */
public record UnlinkedInternalRow(String invoiceUuid, int invoicenumber, String type, String status,
                                  String issuerCompanyName, String debtorCompanyName, String invoicedate,
                                  String description, double total, List<String> itemNames,
                                  String suggestedConsultantUuid, String suggestedConsultantName,
                                  Integer suggestedWorkYear, Integer suggestedWorkMonth) {}
