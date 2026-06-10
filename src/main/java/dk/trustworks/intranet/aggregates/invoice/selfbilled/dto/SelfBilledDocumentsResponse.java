package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

import java.util.List;

public record SelfBilledDocumentsResponse(List<SelfBilledDocumentDTO> documents,
                                          SelfBilledCoverage coverage,
                                          SelfBilledTieOut tieOut) {}
