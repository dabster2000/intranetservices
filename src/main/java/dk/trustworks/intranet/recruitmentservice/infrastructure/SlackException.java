package dk.trustworks.intranet.recruitmentservice.infrastructure;

public class SlackException extends RecruitmentIntegrationException {
    public SlackException(boolean retryable, String errorCode, String detail) {
        super(retryable, errorCode, detail);
    }
    public SlackException(boolean retryable, String errorCode, String detail, Throwable cause) {
        super(retryable, errorCode, detail, cause);
    }
}
