package dk.trustworks.intranet.recruitmentservice.services;

/**
 * Wire-contract error codes returned in the JSON body of upload-failure
 * responses from {@code POST /onboarding/tokens/{token}/upload}.
 *
 * <p>The frontend (`uploadStateMachine.ts`) mirrors these strings. Renaming
 * any of them is a public-API change — both repos must be updated together.</p>
 */
public final class OnboardingErrorCodes {

    public static final String AI_REJECTED              = "AI_REJECTED";
    public static final String ALREADY_SUBMITTED        = "ALREADY_SUBMITTED";
    public static final String EMPTY_FILE               = "EMPTY_FILE";
    public static final String FILE_TOO_LARGE           = "FILE_TOO_LARGE";
    public static final String UNSUPPORTED_MEDIA_TYPE   = "UNSUPPORTED_MEDIA_TYPE";
    public static final String INVALID_FILENAME        = "INVALID_FILENAME";
    public static final String DOCUMENT_TYPE_NOT_ALLOWED = "DOCUMENT_TYPE_NOT_ALLOWED";

    private OnboardingErrorCodes() {}
}
