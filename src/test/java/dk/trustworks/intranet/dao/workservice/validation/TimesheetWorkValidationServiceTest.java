package dk.trustworks.intranet.dao.workservice.validation;

import dk.trustworks.intranet.contracts.config.TimesheetRuleEnforcementConfig;
import dk.trustworks.intranet.contracts.config.TimesheetRuleEnforcementConfig.Mode;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetContractResolver.EligibleContract;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetContractResolver.Resolution;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetValidationPolicyCache.Policy;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetValidationPolicyCache.ValidationRule;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimesheetWorkValidationServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 10);
    private static final String USER = "user-uuid";
    private static final String WORK_AS = "work-as-uuid";
    private static final String CONTRACT = "contract-uuid";
    private static final String TYPE = "DAGROFA_2026";
    private static final String AGREEMENT = "Dagrofa 2026";
    private static final String PROJECT = "project-uuid";
    private static final String CLIENT = "client-uuid";

    @Mock
    TimesheetRuleEnforcementConfig config;
    @Mock
    TimesheetContractResolver contractResolver;
    @Mock
    TimesheetValidationPolicyCache policyCache;
    @Mock
    TimesheetValidationLogWriter validationLogWriter;
    @Mock
    RequestHeaderHolder requestHeaderHolder;

    TimesheetWorkValidationService service;

    @BeforeEach
    void setUp() {
        service = new TimesheetWorkValidationService();
        service.config = config;
        service.contractResolver = contractResolver;
        service.policyCache = policyCache;
        service.validationLogWriter = validationLogWriter;
        service.requestHeaderHolder = requestHeaderHolder;
    }

    @Test
    void offPerformsZeroContractOrRuleLookups() {
        when(config.mode()).thenReturn(Mode.OFF);

        service.validate(work(2.0, null));

        verifyNoInteractions(contractResolver, policyCache, validationLogWriter, requestHeaderHolder);
    }

    @Test
    void zeroHoursClearPerformsNoLookupEvenWhenEnforcing() {
        when(config.mode()).thenReturn(Mode.ENFORCE);

        service.validate(work(0.0, null));

        verifyNoInteractions(contractResolver, policyCache, validationLogWriter, requestHeaderHolder);
    }

    @Test
    void logOnlyAuditsWhitespaceNotesViolationAndStillReturnsNormally() {
        Work work = work(2.0, "   ");
        arrangeResolved(Mode.LOG_ONLY, work, List.of(notesRule(true)));
        when(requestHeaderHolder.getUserUuid()).thenReturn("requested-by");

        assertDoesNotThrow(() -> service.validate(work));

        ArgumentCaptor<TimesheetRuleViolation> violation = ArgumentCaptor.forClass(TimesheetRuleViolation.class);
        verify(validationLogWriter).recordViolation(
                eq(work), eq(USER), eq(CONTRACT), eq(PROJECT), eq("requested-by"),
                eq(Mode.LOG_ONLY), violation.capture());
        assertEquals("NOTES_REQUIRED", violation.getValue().type());
        assertTrue(violation.getValue().message().contains(AGREEMENT));
        assertEquals(CONTRACT, work.getContractuuid(), "resolved per-cell contract is persisted on save");
        assertEquals(PROJECT, work.getProjectuuid());
        assertEquals(CLIENT, work.getClientuuid());
    }

    @Test
    void enforceAggregatesDeterministicViolationsAndCarriesPinnedMetadata() {
        Work work = work(0.5, null);
        arrangeResolved(Mode.ENFORCE, work, List.of(
                notesRule(true),
                minHoursRule("min-hours", "1.0", 20)));
        when(requestHeaderHolder.getUserUuid()).thenReturn("requested-by");

        TimesheetRuleValidationException exception = assertThrows(
                TimesheetRuleValidationException.class,
                () -> service.validate(work));

        assertEquals("TIMESHEET_RULE_VIOLATION", exception.getCode());
        assertEquals(CONTRACT, exception.getContractUuid());
        assertEquals(TYPE, exception.getContractTypeCode());
        assertEquals(AGREEMENT, exception.getAgreementName());
        assertEquals(List.of("NOTES_REQUIRED", "MIN_HOURS_PER_ENTRY"),
                exception.getViolations().stream().map(TimesheetRuleViolation::type).toList());
        verify(validationLogWriter, org.mockito.Mockito.times(2)).recordViolation(
                eq(work), eq(USER), eq(CONTRACT), eq(PROJECT), eq("requested-by"),
                eq(Mode.ENFORCE), any(TimesheetRuleViolation.class));
    }

    @Test
    void optionalNotesRuleIsIgnored() {
        Work work = work(2.0, null);
        arrangeResolved(Mode.ENFORCE, work, List.of(notesRule(false)));

        assertDoesNotThrow(() -> service.validate(work));

        verifyNoInteractions(validationLogWriter);
    }

    @Test
    void minHoursBelowFailsWhileEqualAndAbovePass() {
        Policy policy = policy(List.of(minHoursRule("min-hours", "1.0", 10)));

        assertEquals(1, TimesheetWorkValidationService.evaluate(policy, work(0.99, "ok")).size());
        assertTrue(TimesheetWorkValidationService.evaluate(policy, work(1.0, "ok")).isEmpty());
        assertTrue(TimesheetWorkValidationService.evaluate(policy, work(1.01, "ok")).isEmpty());
    }

    @Test
    void workAsIsTheEffectiveConsultantForResolutionAndAudit() {
        Work work = work(2.0, "notes");
        work.setWorkas(WORK_AS);
        when(config.mode()).thenReturn(Mode.LOG_ONLY);
        when(contractResolver.resolve(work, WORK_AS)).thenReturn(Resolution.resolved(contract()));
        when(policyCache.getPolicy(contract(), DATE)).thenReturn(policy(List.of()));

        service.validate(work);

        verify(contractResolver).resolve(work, WORK_AS);
        verify(validationLogWriter, never()).recordViolation(
                any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void unresolvedContractLogsButSavesInLogOnlyAndRejectsInEnforce() {
        Work logOnlyWork = work(2.0, "notes");
        when(config.mode()).thenReturn(Mode.LOG_ONLY);
        when(requestHeaderHolder.getUserUuid()).thenReturn("requested-by");
        when(contractResolver.resolve(logOnlyWork, USER)).thenReturn(
                Resolution.unresolved("bad-contract", "Contract bad-contract is not eligible for this work entry.", 0));

        assertDoesNotThrow(() -> service.validate(logOnlyWork));
        verify(validationLogWriter).recordViolation(
                eq(logOnlyWork), eq(USER), eq("bad-contract"), eq(null), eq("requested-by"),
                eq(Mode.LOG_ONLY), any(TimesheetRuleViolation.class));
        verifyNoInteractions(policyCache);

        Work enforcedWork = work(2.0, "notes");
        when(config.mode()).thenReturn(Mode.ENFORCE);
        when(contractResolver.resolve(enforcedWork, USER)).thenReturn(
                Resolution.ambiguous("Multiple eligible contracts match this work entry; supply contractUuid.", 2));

        TimesheetRuleValidationException exception = assertThrows(
                TimesheetRuleValidationException.class,
                () -> service.validate(enforcedWork));
        assertEquals("TIMESHEET_CONTRACT_UNRESOLVED", exception.getCode());
        assertEquals(2, exception.getViolations().getFirst().actual());
    }

    @Test
    void positiveWorkWithNoAgreementContractSkipsPhase4Rules() {
        Work internalWork = work(7.4, "Internal task");
        when(config.mode()).thenReturn(Mode.ENFORCE);
        when(contractResolver.resolve(internalWork, USER)).thenReturn(
                Resolution.noApplicableAgreement(null));

        assertDoesNotThrow(() -> service.validate(internalWork));

        verifyNoInteractions(policyCache, validationLogWriter, requestHeaderHolder);
    }

    @Test
    void paidOutExistingEntrySkipsPhase4RulesBecauseSaveIsNoop() {
        Work paidOutWork = work(0.5, null);
        when(config.mode()).thenReturn(Mode.ENFORCE);
        when(contractResolver.resolve(paidOutWork, USER)).thenReturn(Resolution.paidOut(CONTRACT));

        assertDoesNotThrow(() -> service.validate(paidOutWork));

        verifyNoInteractions(policyCache, validationLogWriter, requestHeaderHolder);
    }

    @Test
    void nonBlankNotesSatisfyRequiredRule() {
        Policy policy = policy(List.of(notesRule(true)));
        List<TimesheetRuleViolation> violations = TimesheetWorkValidationService.evaluate(
                policy, work(1.0, " customer reference "));
        assertTrue(violations.isEmpty());
    }

    private void arrangeResolved(Mode mode, Work work, List<ValidationRule> rules) {
        when(config.mode()).thenReturn(mode);
        when(contractResolver.resolve(work, USER)).thenReturn(Resolution.resolved(contract()));
        when(policyCache.getPolicy(contract(), DATE)).thenReturn(policy(rules));
    }

    private static Work work(double hours, String comments) {
        Work work = new Work();
        work.setUuid("work-uuid");
        work.setRegistered(DATE);
        work.setUseruuid(USER);
        work.setTaskuuid("task-uuid");
        work.setWorkduration(hours);
        work.setComments(comments);
        return work;
    }

    private static EligibleContract contract() {
        return new EligibleContract(CONTRACT, TYPE, AGREEMENT, PROJECT, CLIENT);
    }

    private static Policy policy(List<ValidationRule> rules) {
        return new Policy(CONTRACT, TYPE, AGREEMENT, DATE, rules);
    }

    private static ValidationRule notesRule(boolean required) {
        return new ValidationRule("notes", ValidationType.NOTES_REQUIRED, required, null, 10);
    }

    private static ValidationRule minHoursRule(String id, String threshold, int priority) {
        return new ValidationRule(
                id, ValidationType.MIN_HOURS_PER_ENTRY, false, new BigDecimal(threshold), priority);
    }
}
