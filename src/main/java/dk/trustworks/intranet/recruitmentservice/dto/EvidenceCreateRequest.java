package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The Phase 14 manual-review action's body (plan §14): the recruiter
 * converts an interviewer's unparseable message into normalized
 * constraints by hand. {@code userUuid} is the interviewer the
 * availability belongs to — validated against the request's
 * participants by the resource.
 */
public record EvidenceCreateRequest(
        String userUuid,
        LocalDate coveredFrom,
        LocalDate coveredTo,
        List<ConstraintInput> constraints
) {

    /** One hand-entered interval. */
    public record ConstraintInput(
            AvailabilityConstraintType type,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
    }
}
