package dk.trustworks.intranet.agreementservice.dto;

import dk.trustworks.intranet.agreementservice.services.AgreementExtractionService.Proposal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One backfill document + its proposals + review state (template-clauses
 * spec §4.8/§10). The review queue groups these by employee client-side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackfillItemDTO {
    private String uuid;
    private String runUuid;
    private String userUuid;
    private String userName;
    private String fileName;
    private long fileSize;
    private String webUrl;
    private String status;
    private List<Proposal> proposals;
    private String extractionNote;
    private String reviewedBy;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private List<String> createdAgreementUuids;
    private LocalDateTime createdAt;
}
