package dk.trustworks.intranet.dao.workservice.validation;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/** Preserves the structured 422 body instead of letting the global mapper discard it. */
@Provider
@JBossLog
public class TimesheetRuleValidationExceptionMapper
        implements ExceptionMapper<TimesheetRuleValidationException> {

    static final int UNPROCESSABLE_ENTITY = 422;

    @Override
    public Response toResponse(TimesheetRuleValidationException exception) {
        log.debugf("Timesheet validation failed -> 422: code=%s, contractUuid=%s, violations=%d",
                exception.getCode(), exception.getContractUuid(), exception.getViolations().size());

        TimesheetValidationProblem body = new TimesheetValidationProblem(
                TimesheetRuleValidationException.ERROR,
                UNPROCESSABLE_ENTITY,
                exception.getCode(),
                exception.getContractUuid(),
                exception.getContractTypeCode(),
                exception.getAgreementName(),
                exception.getViolations());

        return Response.status(UNPROCESSABLE_ENTITY)
                .entity(body)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * Typed problem envelope consumed by both timesheet clients.
     * Field names intentionally match the pinned Phase 4 API contract.
     */
    public record TimesheetValidationProblem(
            String error,
            int status,
            String code,
            String contractUuid,
            String contractTypeCode,
            String agreementName,
            List<TimesheetRuleViolation> violations) {

        public TimesheetValidationProblem {
            violations = List.copyOf(violations);
        }
    }
}
