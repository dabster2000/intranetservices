package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Per-candidate GDPR action state for the profile's GDPR tab (ATS P19):
 * which DPO actions apply right now and why. Pure presentation input —
 * the backend re-checks every rule on the action endpoints.
 */
public record GdprCandidateStatusResponse(
        /** Candidate status name (ANONYMIZED/HIRED disable the actions). */
        String candidateStatus,
        boolean hasEmail,
        /** Art. 14 notice applies to this candidate's source. */
        boolean art14Required,
        /** A notice was already sent (event-derived). */
        boolean art14NoticeSent,
        /** A DSAR is recorded and not yet answered by an export. */
        boolean openDsar,
        /** Anonymization is possible (not HIRED, not already anonymized). */
        boolean canAnonymize) {
}
