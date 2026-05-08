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
 */
public record OnboardingValidateResponse(
        boolean valid,
        boolean expired,
        FieldFlags fields,
        Submitted submitted
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

    public static OnboardingValidateResponse ofInvalid() {
        return new OnboardingValidateResponse(false, false,
                new FieldFlags(false, false, false), Submitted.none());
    }

    public static OnboardingValidateResponse ofExpired() {
        return new OnboardingValidateResponse(false, true,
                new FieldFlags(false, false, false), Submitted.none());
    }
}
