package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.dao.workservice.services.MonthSubmissionService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetRuleValidationException;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetRuleViolation;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HTTP contract for Phase 4 work-save rejection and its no-event guarantee. */
@QuarkusTest
class WorkResourceTimesheetValidationTest {

    @InjectMock WorkService workService;
    @InjectMock MonthSubmissionService monthSubmissionService;
    @InjectMock AggregateEventSender eventSender;

    @Test
    @TestSecurity(user = "timesheet-writer", roles = {"timeregistration:write"})
    void postWorkReturnsStructured422AndDoesNotPublishEvent() {
        when(monthSubmissionService.isMonthLocked(anyString(), anyInt(), anyInt())).thenReturn(false);
        doThrow(new TimesheetRuleValidationException(
                TimesheetRuleValidationException.RULE_VIOLATION_CODE,
                "contract-uuid",
                "DAGROFA2026",
                "Dagrofa 2026",
                List.of(new TimesheetRuleViolation(
                        "notes-required",
                        "NOTES_REQUIRED",
                        "Notes are required for this contract (Dagrofa 2026).",
                        true,
                        false))))
                .when(workService).persistOrUpdate(any());

        given()
                .header("X-Requested-By", "timesheet-writer")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "uuid": "work-uuid",
                          "useruuid": "user-uuid",
                          "taskuuid": "task-uuid",
                          "contractuuid": "contract-uuid",
                          "registered": "2026-07-10",
                          "workduration": 1.0,
                          "comments": ""
                        }
                        """)
        .when()
                .post("/work")
        .then()
                .statusCode(422)
                .contentType(MediaType.APPLICATION_JSON)
                .body("error", equalTo("Timesheet rule validation failed"))
                .body("status", equalTo(422))
                .body("code", equalTo("TIMESHEET_RULE_VIOLATION"))
                .body("contractUuid", equalTo("contract-uuid"))
                .body("contractTypeCode", equalTo("DAGROFA2026"))
                .body("agreementName", equalTo("Dagrofa 2026"))
                .body("violations[0].ruleId", equalTo("notes-required"))
                .body("violations[0].threshold", equalTo(true))
                .body("violations[0].actual", equalTo(false));

        verify(eventSender, never()).handleEvent(any());
    }
}
