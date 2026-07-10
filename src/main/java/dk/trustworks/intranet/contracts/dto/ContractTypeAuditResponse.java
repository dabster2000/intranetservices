package dk.trustworks.intranet.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response envelope for {@code GET /api/contract-types/{code}/audit}: {@code {"entries":[...]}}
 * with entries newest-first (cross-repo contract C4).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContractTypeAuditResponse {

    /** Audit entries, newest first. */
    private List<ContractTypeAuditEntryDTO> entries;
}
