package dk.trustworks.intranet.recruitmentservice.infrastructure;

public abstract class RecruitmentIntegrationException extends RuntimeException {
    private final boolean retryable;
    private final String errorCode;
    private final String detail;

    protected RecruitmentIntegrationException(boolean retryable, String errorCode, String detail) {
        this(retryable, errorCode, detail, null);
    }

    protected RecruitmentIntegrationException(boolean retryable, String errorCode, String detail, Throwable cause) {
        super("[" + errorCode + "] " + detail, cause);
        this.retryable = retryable;
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public boolean isRetryable() { return retryable; }
    public String getErrorCode() { return errorCode; }
    public String getDetail() { return detail; }
}
