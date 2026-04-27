package dk.trustworks.intranet.recruitmentservice.infrastructure;

public class OutlookCalendarException extends RecruitmentIntegrationException {
    public OutlookCalendarException(boolean retryable, String errorCode, String detail) {
        super(retryable, errorCode, detail);
    }
    public OutlookCalendarException(boolean retryable, String errorCode, String detail, Throwable cause) {
        super(retryable, errorCode, detail, cause);
    }
}
