package dk.trustworks.intranet.aggregates.bonus.individual.resources;

import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import dk.trustworks.intranet.aggregates.bonus.individual.model.Spec;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndividualBonusResourceMonthlyContractTest {

    private IndividualBonusResource resource;

    @BeforeEach
    void setUp() {
        resource = new IndividualBonusResource();
        resource.scopeContext = mock(ScopeContext.class);
        when(resource.scopeContext.hasScope("bonus:write")).thenReturn(true);
        resource.requestHeaderHolder = new RequestHeaderHolder();
        resource.requestHeaderHolder.setUserUuid("00000000-0000-0000-0000-000000000001");
    }

    @Test
    void monthlyUpdatePreviewRejectsMissingAndBlankRuleUuidBeforeLookup() {
        IndividualBonusException missing = assertThrows(IndividualBonusException.class,
                () -> resource.preview("employee", "UPDATE", null, null,
                        monthlyRequest(3L, null)));
        IndividualBonusException blank = assertThrows(IndividualBonusException.class,
                () -> resource.preview("employee", "UPDATE", "  ", null,
                        monthlyRequest(3L, null)));

        assertFailure(missing, "RULE_UUID_REQUIRED", "ruleUuid");
        assertFailure(blank, "RULE_UUID_REQUIRED", "ruleUuid");
    }

    @Test
    void monthlyCreatePreviewRejectsQueryRuleUuid() {
        IndividualBonusException failure = assertThrows(IndividualBonusException.class,
                () -> resource.preview("employee", "CREATE", "client-rule", null,
                        monthlyRequest(null, null)));

        assertFailure(failure, "RULE_UUID_NOT_ALLOWED", "ruleUuid");
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
