package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Request bodies of the P19 DPO actions ({@code /recruitment/gdpr/...}).
 * Kept together — they are two-field shells for a single resource.
 */
public final class GdprActionRequests {

    private GdprActionRequests() {
    }

    /** Body of {@code POST /gdpr/candidates/{uuid}/art14-send}. */
    public static class Art14SendRequest {
        /**
         * {@code true} = the DPO notified the candidate outside the system
         * (phone, LinkedIn message) and records that fact; {@code false}
         * (default) = send the ART14_NOTICE template email now.
         */
        public boolean manual;
        /** Optional context, e.g. how the manual notice was given. Stored as event pii. */
        public String note;
    }

    /** Body of {@code POST /gdpr/candidates/{uuid}/dsar}. */
    public static class DsarRecordRequest {
        /** Optional context, e.g. how the request arrived. Stored as event pii. */
        public String note;
    }

    /** Body of {@code POST /gdpr/candidates/{uuid}/anonymize}. */
    public static class AnonymizeRequest {
        /**
         * Typed confirmation (plan §P19: anonymization is irreversible and
         * requires typed confirmation): must equal the candidate's current
         * full name exactly. Checked server-side too — the UI dialog is
         * convenience, not the guard.
         */
        public String confirmText;
    }
}
