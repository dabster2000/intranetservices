package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;

/**
 * Body for {@code POST /recruitment/candidates/{uuid}/pool}. The bucket is
 * optional — omitted means {@link CandidatePoolStatus#PROSPECT}.
 */
public record PoolRequest(CandidatePoolStatus poolStatus) {
}
