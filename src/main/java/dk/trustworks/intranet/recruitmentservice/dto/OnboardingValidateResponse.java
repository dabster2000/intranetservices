package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Public-page response for {@code GET /onboarding/tokens/{uuid}/validate}
 * (and the upload endpoint, which returns the refreshed status so the UI
 * can lock zones without a second round trip).
 *
 * <p>{@link #fields} reflects which document types the token <i>asks</i>
 * for. {@link #submitted} reflects which of those have already been
 * uploaded — once a type is {@code true} the corresponding upload zone
 * must be locked client-side.</p>
 *
 * <p>{@link #displayName} lets the visitor confirm the link is theirs
 * before uploading anything — see its own doc for the disclosure
 * boundary. It is {@code null} on every invalid / expired response so
 * the endpoint's silence rule is unchanged.</p>
 */
public record OnboardingValidateResponse(
        boolean valid,
        boolean expired,
        FieldFlags fields,
        Submitted submitted,
        /**
         * <b>Masked</b> name of the token owner — given name plus the initial
         * of the surname ("Henrik F."), never the full name.
         *
         * <p>Rationale: the upload page showed no recipient identity at all,
         * so a mis-sent link was undetectable — a candidate could upload their
         * passport and health-insurance card into a colleague's personnel file
         * with nothing on screen to warn them. The visitor needs enough to
         * answer "is this me?", which a given name plus one initial does.</p>
         *
         * <p>The endpoint is {@code @PermitAll}, so anything returned here is
         * disclosed to whoever holds the token. Masking keeps that disclosure
         * strictly smaller than what the token already grants (the right to
         * write identity documents into that person's file), while still
         * distinguishing the two people a mis-send realistically confuses.
         * {@code null} whenever the owner row is missing or unnamed — the
         * page then degrades to its previous no-identity behaviour.</p>
         */
        String displayName
) {
    public record FieldFlags(
            boolean driversLicense,
            boolean healthInsurance,
            boolean criminalRecord
    ) {}

    public record Submitted(
            boolean driversLicense,
            boolean healthInsurance,
            boolean criminalRecord
    ) {
        public static Submitted none() {
            return new Submitted(false, false, false);
        }
    }

    /** No name: an unknown token must stay indistinguishable from a known one. */
    public static OnboardingValidateResponse ofInvalid() {
        return new OnboardingValidateResponse(false, false,
                new FieldFlags(false, false, false), Submitted.none(), null);
    }

    /**
     * No name either. An expired token already tells the caller the token
     * existed; naming its owner would upgrade that to "and it belonged to X"
     * for a link nobody can use any more.
     */
    public static OnboardingValidateResponse ofExpired() {
        return new OnboardingValidateResponse(false, true,
                new FieldFlags(false, false, false), Submitted.none(), null);
    }
}
