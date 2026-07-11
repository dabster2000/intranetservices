package dk.trustworks.intranet.dao.workservice.validation;

import dk.trustworks.intranet.dao.workservice.validation.TimesheetRuleValidationExceptionMapper.TimesheetValidationProblem;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TimesheetRuleValidationExceptionMapperTest {

    @Test
    void mapsExactPinned422EnvelopeWithoutGlobalMapperDataLoss() {
        TimesheetRuleViolation violation = new TimesheetRuleViolation(
                "min-hours", "MIN_HOURS_PER_ENTRY", "Agreement-specific message",
                BigDecimal.ONE, new BigDecimal("0.5"));
        TimesheetRuleValidationException exception = new TimesheetRuleValidationException(
                "TIMESHEET_RULE_VIOLATION",
                "contract-uuid",
                "DAGROFA_2026",
                "Dagrofa 2026",
                List.of(violation));

        Response response = new TimesheetRuleValidationExceptionMapper().toResponse(exception);

        assertEquals(422, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        assertInstanceOf(TimesheetValidationProblem.class, response.getEntity());
        TimesheetValidationProblem body = (TimesheetValidationProblem) response.getEntity();
        assertEquals("Timesheet rule validation failed", body.error());
        assertEquals(422, body.status());
        assertEquals("TIMESHEET_RULE_VIOLATION", body.code());
        assertEquals("contract-uuid", body.contractUuid());
        assertEquals("DAGROFA_2026", body.contractTypeCode());
        assertEquals("Dagrofa 2026", body.agreementName());
        assertEquals(List.of(violation), body.violations());
    }
}
