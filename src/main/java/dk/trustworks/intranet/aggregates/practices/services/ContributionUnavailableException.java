package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.ws.rs.ServiceUnavailableException;

/** A closed, caller-safe availability failure for the contribution aggregate. */
public final class ContributionUnavailableException extends ServiceUnavailableException {
    public static final String PUBLICATION_UNAVAILABLE =
            "PRACTICE_CONTRIBUTION_PUBLICATION_UNAVAILABLE";
    public static final String PUBLICATION_DISABLED =
            "PRACTICE_CONTRIBUTION_PUBLICATION_DISABLED";
    public static final String QUERY_TIMEOUT =
            "PRACTICE_CONTRIBUTION_QUERY_TIMEOUT";

    public ContributionUnavailableException(String safeCode) {
        super(requireSafeCode(safeCode));
    }

    private static String requireSafeCode(String code) {
        if (!PUBLICATION_UNAVAILABLE.equals(code)
                && !PUBLICATION_DISABLED.equals(code)
                && !QUERY_TIMEOUT.equals(code)) {
            throw new IllegalArgumentException("unsupported contribution error code");
        }
        return code;
    }
}
