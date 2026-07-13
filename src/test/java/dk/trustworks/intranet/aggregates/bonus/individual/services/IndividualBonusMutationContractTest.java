package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndividualBonusMutationContractTest {

    @Test
    void monthlyCreateRejectsNonNullRevision() {
        IndividualBonusMutationService service = new IndividualBonusMutationService();
        IndividualBonusException failure = assertThrows(IndividualBonusException.class,
                () -> service.create(monthlyRequest(0L, null), null, null, null));

        assertFailure(failure, "RULE_REVISION_NOT_ALLOWED", "revision");
    }

    @Test
    void monthlyCreateRejectsAnyBodyRuleUuidIncludingBlank() {
        IndividualBonusMutationService service = new IndividualBonusMutationService();
        IndividualBonusException populated = assertThrows(IndividualBonusException.class,
                () -> service.create(monthlyRequest(null, "client-rule"), null, null, null));
        IndividualBonusException blank = assertThrows(IndividualBonusException.class,
                () -> service.create(monthlyRequest(null, ""), null, null, null));

        assertFailure(populated, "RULE_UUID_NOT_ALLOWED", "ruleUuid");
        assertFailure(blank, "RULE_UUID_NOT_ALLOWED", "ruleUuid");
    }

    @Test
    void monthlyCreatePreviewRejectsAnyQueryRuleUuid() {
        IndividualBonusException failure = assertThrows(IndividualBonusException.class,
                () -> IndividualBonusMutationService.validateMonthlyCreateContract(
                        monthlyRequest(null, null), ""));

        assertFailure(failure, "RULE_UUID_NOT_ALLOWED", "ruleUuid");
    }

    @Test
    void monthlyUpdatePreviewRequiresNonBlankRuleUuid() {
        IndividualBonusException missing = assertThrows(IndividualBonusException.class,
                () -> IndividualBonusMutationService.validateMonthlyUpdatePreviewRuleUuid("UPDATE", null));
        IndividualBonusException blank = assertThrows(IndividualBonusException.class,
                () -> IndividualBonusMutationService.validateMonthlyUpdatePreviewRuleUuid("UPDATE", "  "));

        assertFailure(missing, "RULE_UUID_REQUIRED", "ruleUuid");
        assertFailure(blank, "RULE_UUID_REQUIRED", "ruleUuid");
        assertDoesNotThrow(() -> IndividualBonusMutationService.validateMonthlyUpdatePreviewRuleUuid(
                "UPDATE", "authoritative-rule"));
    }

    private static IndividualBonusRuleRequest monthlyRequest(Long revision, String ruleUuid) {
        Spec monthly = new Spec(null, "CALENDAR_MONTH", null, null,
                null, null, null, false, null, null, null);
        return new IndividualBonusRuleRequest("employee", "Monthly bonus", LocalDate.of(2026, 7, 1),
                null, null, true, monthly, revision, ruleUuid);
    }

    private static void assertFailure(IndividualBonusException failure, String code, String field) {
        assertEquals(400, failure.status());
        assertEquals(code, failure.code());
        assertEquals(field, failure.field());
    }
}
