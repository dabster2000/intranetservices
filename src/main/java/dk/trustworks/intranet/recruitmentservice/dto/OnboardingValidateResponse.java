package dk.trustworks.intranet.recruitmentservice.dto;

public record OnboardingValidateResponse(
        boolean valid,
        boolean expired,
        FieldFlags fields
) {
    public record FieldFlags(
            boolean driversLicense,
            boolean healthInsurance,
            boolean criminalRecord
    ) {}

    public static OnboardingValidateResponse ofInvalid() {
        return new OnboardingValidateResponse(false, false, new FieldFlags(false, false, false));
    }

    public static OnboardingValidateResponse ofExpired() {
        return new OnboardingValidateResponse(false, true, new FieldFlags(false, false, false));
    }
}
