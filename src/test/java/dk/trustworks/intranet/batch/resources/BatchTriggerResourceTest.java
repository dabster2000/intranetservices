package dk.trustworks.intranet.batch.resources;

import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueSourceRecoveryService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.batch.operations.JobOperator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchTriggerResourceTest {

    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";
    private static final String GENERATION = "22222222-2222-2222-2222-222222222222";
    private static final String OWNER = "33333333-3333-3333-3333-333333333333";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    private JobOperator jobOperator;
    private EntityManager em;
    private PracticeRevenueSourceRecoveryService recoveryService;
    private BatchTriggerResource resource;

    @BeforeEach
    void setUp() {
        jobOperator = mock(JobOperator.class);
        em = mock(EntityManager.class);
        recoveryService = mock(PracticeRevenueSourceRecoveryService.class);
        resource = new BatchTriggerResource();
        resource.jobOperator = jobOperator;
        resource.em = em;
        resource.recoveryService = recoveryService;
    }

    @Test
    void exposesOnlyTheClosedSystemWritePostSurface() {
        assertEquals("/system/batch", BatchTriggerResource.class.getAnnotation(Path.class).value());
        assertEquals(Set.of("system:write"), Set.of(
                BatchTriggerResource.class.getAnnotation(RolesAllowed.class).value()));

        Set<String> paths = Arrays.stream(BatchTriggerResource.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(POST.class))
                .map(method -> method.getAnnotation(Path.class).value())
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "/{action}/start",
                "/practice-cost-basis-refresh/start",
                "/practice-revenue-refresh/start",
                "/practice-revenue-refresh/recovery-start",
                "/practice-revenue-publication/enable-build",
                "/practice-revenue-publication/enable-serving",
                "/practice-revenue-publication/disable",
                "/practice-cost-basis-publication/disable-serving",
                "/practice-cost-basis-publication/enable-serving",
                "/practice-revenue-source-recovery/DELIVERY_EVIDENCE/start",
                "/practice-revenue-source-recovery/{category}/start"), paths);
    }

    @Test
    void legacyFinanceStartMapsOnlyTwoClosedActionsToFixedJobNames() {
        assertEquals(Set.of(
                        BatchTriggerResource.FinanceBatchAction.FINANCE_LOAD_ECONOMICS,
                        BatchTriggerResource.FinanceBatchAction.FINANCE_INVOICE_SYNC),
                Set.of(BatchTriggerResource.FinanceBatchAction.values()));
        when(jobOperator.getJobNames()).thenReturn(Set.of());
        when(jobOperator.start(eq("finance-load-economics"), any(Properties.class))).thenReturn(41L);

        Response ok = resource.start("finance-load-economics");
        Response rejected = resource.start("practice-revenue-refresh");

        assertEquals(200, ok.getStatus());
        assertEquals(new BatchTriggerResource.ExecutionResponse(
                "finance-load-economics", "41", 41L), ok.getEntity());
        assertEquals(400, rejected.getStatus());
        assertEquals(new BatchTriggerResource.ErrorResponse("UNKNOWN_BATCH_ACTION"), rejected.getEntity());
        verify(jobOperator).start(eq("finance-load-economics"), any(Properties.class));
        verify(jobOperator, never()).start(eq("practice-revenue-refresh"), any(Properties.class));
    }

    @Test
    void costStartRequiresCanonicalActorAndExactRequestIdentity() {
        BatchTriggerResource.CostBasisStartRequest request = new BatchTriggerResource.CostBasisStartRequest(
                true, BigInteger.valueOf(17), HASH_A, HASH_B);

        Response nonUuid = resource.startCostBasis(request, "operator");
        Response uppercaseHash = resource.startCostBasis(
                new BatchTriggerResource.CostBasisStartRequest(
                        true, BigInteger.valueOf(17), HASH_A.toUpperCase(), HASH_B), ACTOR);

        assertEquals(400, nonUuid.getStatus());
        assertEquals(400, uppercaseHash.getStatus());
        verifyNoInteractions(em, jobOperator);
    }

    @Test
    void eligibleCostStartBindsExactIdentityAndStartsOnlyTheFixedCostJob() {
        Query query = query();
        when(em.createNativeQuery(BatchTriggerResource.COST_START_PRECONDITION_SQL)).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);
        when(jobOperator.getJobNames()).thenReturn(Set.of());
        when(jobOperator.start(eq(BatchTriggerResource.COST_JOB), any(Properties.class))).thenReturn(51L);
        BatchTriggerResource.CostBasisStartRequest request = new BatchTriggerResource.CostBasisStartRequest(
                true, BigInteger.valueOf(17), HASH_A, HASH_B);

        Response response = resource.startCostBasis(request, ACTOR);

        assertEquals(200, response.getStatus());
        assertEquals(new BatchTriggerResource.CostBasisExecutionResponse(
                BatchTriggerResource.COST_JOB, "51", 51L, BigInteger.valueOf(17)), response.getEntity());
        verify(query).setParameter("requestId", BigInteger.valueOf(17));
        verify(query).setParameter("requestKey", HASH_A);
        verify(query).setParameter("inputVector", HASH_B);
        ArgumentCaptor<Properties> parameters = ArgumentCaptor.forClass(Properties.class);
        verify(jobOperator).start(eq(BatchTriggerResource.COST_JOB), parameters.capture());
        assertEquals(Set.of("expectedRequestId", "expectedRequestKey", "expectedInputVectorFingerprint"),
                parameters.getValue().stringPropertyNames());
        assertEquals("17", parameters.getValue().getProperty("expectedRequestId"));
        assertEquals(HASH_A, parameters.getValue().getProperty("expectedRequestKey"));
        assertEquals(HASH_B, parameters.getValue().getProperty("expectedInputVectorFingerprint"));
        verify(jobOperator, never()).start(eq(BatchTriggerResource.REVENUE_JOB), any(Properties.class));
    }

    @Test
    void runningCostJobReturnsClosedConflictWithoutStartingAnotherExecution() {
        Query query = query();
        when(em.createNativeQuery(BatchTriggerResource.COST_START_PRECONDITION_SQL)).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);
        when(jobOperator.getJobNames()).thenReturn(Set.of(BatchTriggerResource.COST_JOB));
        when(jobOperator.getRunningExecutions(BatchTriggerResource.COST_JOB)).thenReturn(java.util.List.of(7L));

        Response response = resource.startCostBasis(new BatchTriggerResource.CostBasisStartRequest(
                true, BigInteger.valueOf(17), HASH_A, HASH_B), ACTOR);

        assertEquals(409, response.getStatus());
        assertEquals(new BatchTriggerResource.ErrorResponse("JOB_ALREADY_RUNNING"), response.getEntity());
        verify(jobOperator, never()).start(anyString(), any(Properties.class));
    }

    @Test
    void ordinaryRevenueStartRequiresCoherentPreconditionsAndStartsOnlyFixedRevenueJob() {
        Query query = query();
        when(em.createNativeQuery(BatchTriggerResource.REVENUE_START_PRECONDITION_SQL)).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);
        when(jobOperator.getJobNames()).thenReturn(Set.of());
        when(jobOperator.start(eq(BatchTriggerResource.REVENUE_JOB), any(Properties.class))).thenReturn(55L);

        Response response = resource.startRevenue(new BatchTriggerResource.ConfirmedAction(true), ACTOR);

        assertEquals(200, response.getStatus());
        assertTrue(BatchTriggerResource.REVENUE_START_PRECONDITION_SQL.contains(
                "o.latest_cost_basis_request_id=o.certified_cost_basis_request_id"));
        assertTrue(BatchTriggerResource.REVENUE_START_PRECONDITION_SQL.contains(
                "r.dependency_fingerprint=basis.dependency_manifest_fingerprint"));
        assertTrue(BatchTriggerResource.REVENUE_START_PRECONDITION_SQL.contains(
                "FROM practice_cost_basis_refresh_request newer"));
        assertTrue(BatchTriggerResource.REVENUE_START_PRECONDITION_SQL.contains(
                "newer.request_id>r.request_id"));
        verify(jobOperator).start(eq(BatchTriggerResource.REVENUE_JOB), any(Properties.class));
        verify(jobOperator, never()).start(eq(BatchTriggerResource.COST_JOB), any(Properties.class));
    }

    @Test
    void revenueRecoveryInstallsOnlyItsClosedFivePartIdentityAndStartsNoCostOrBiJob() {
        Query query = query();
        when(em.createNativeQuery(BatchTriggerResource.RECOVERY_START_SQL)).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);
        when(jobOperator.getJobNames()).thenReturn(Set.of());
        when(jobOperator.start(eq(BatchTriggerResource.REVENUE_JOB), any(Properties.class))).thenReturn(61L);
        BatchTriggerResource.RevenueRecoveryStartRequest request =
                new BatchTriggerResource.RevenueRecoveryStartRequest(
                        true, "2026-07-14T12:30:00Z", BigInteger.valueOf(19),
                        HASH_A, HASH_B, HASH_C);

        Response response = resource.startRevenueRecovery(request, ACTOR);

        assertEquals(200, response.getStatus());
        verify(query).setParameter("requestId", BigInteger.valueOf(19));
        verify(query).setParameter("requestKey", HASH_A);
        verify(query).setParameter("inputVector", HASH_B);
        verify(query).setParameter("sourceVector", HASH_C);
        verify(jobOperator, never()).start(eq(BatchTriggerResource.COST_JOB), any(Properties.class));
        ArgumentCaptor<Properties> parameters = ArgumentCaptor.forClass(Properties.class);
        verify(jobOperator).start(eq(BatchTriggerResource.REVENUE_JOB), parameters.capture());
        assertEquals(Set.of("recoveryExecutionId", "recoveryOwnerToken"),
                parameters.getValue().stringPropertyNames());
        assertFalse(parameters.getValue().getProperty("recoveryExecutionId").isBlank());
        assertFalse(parameters.getValue().getProperty("recoveryOwnerToken").isBlank());
    }

    @Test
    void ordinaryServingEnableUsesGenerationSpecificCurrentSourceAndRequestCas() {
        Query query = query();
        when(em.createNativeQuery(BatchTriggerResource.ENABLE_SERVING_SQL)).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        Response response = resource.enableContributionServing(
                new BatchTriggerResource.ContributionServingRequest(
                        true, GENERATION, "2026-07-14T12:30:00Z", null), ACTOR);

        assertEquals(200, response.getStatus());
        verify(query).setParameter("generation", GENERATION);
        assertTrue(BatchTriggerResource.ENABLE_SERVING_SQL.contains(
                "o.latest_cost_basis_request_id=o.certified_cost_basis_request_id"));
        assertTrue(BatchTriggerResource.ENABLE_SERVING_SQL.contains(
                "req.dependency_fingerprint=basis.dependency_manifest_fingerprint"));
        assertTrue(BatchTriggerResource.ENABLE_SERVING_SQL.contains(
                "p.full_bi_refresh_version=b.full_refresh_version"));
        assertTrue(BatchTriggerResource.ENABLE_SERVING_SQL.contains("w.source_state <> 'READY'"));
        assertTrue(BatchTriggerResource.ENABLE_SERVING_SQL.contains("COUNT(*) FROM practice_revenue_source_watermark"));
    }

    @Test
    void controlSqlKeepsBuildServingAndRecoveryTransitionsClosedAndIndependent() {
        assertTrue(BatchTriggerResource.ENABLE_BUILD_SQL.contains("SET refresh_enabled=TRUE"));
        assertFalse(BatchTriggerResource.ENABLE_BUILD_SQL.contains("contribution_serving_enabled=TRUE"));
        assertFalse(BatchTriggerResource.ENABLE_BUILD_SQL.contains("legacy_cost_serving_enabled=TRUE"));
        assertTrue(BatchTriggerResource.DISABLE_COST_SERVING_SQL.contains("refresh_enabled=FALSE"));
        assertTrue(BatchTriggerResource.DISABLE_COST_SERVING_SQL.contains(
                "contribution_serving_enabled=FALSE"));
        assertTrue(BatchTriggerResource.DISABLE_COST_SERVING_SQL.contains(
                "legacy_cost_serving_enabled=FALSE"));
        assertTrue(BatchTriggerResource.CLEAR_BUILT_RECOVERY_SQL.contains(
                "revenue_recovery_state='BUILT'"));
        assertFalse(BatchTriggerResource.CLEAR_BUILT_RECOVERY_SQL.contains(
                "revenue_recovery_state='RUNNING'"));
        assertTrue(BatchTriggerResource.RECOVERY_START_SQL.contains(
                "c.refresh_enabled=FALSE"));
        assertTrue(BatchTriggerResource.RECOVERY_START_SQL.contains(
                "c.legacy_cost_serving_enabled=TRUE"));
        assertTrue(BatchTriggerResource.RECOVERY_START_SQL.contains(
                "r.dependency_fingerprint=basis.dependency_manifest_fingerprint"));
        assertTrue(BatchTriggerResource.RECOVERY_START_SQL.contains("HAVING COUNT(*)=9"));
        assertFalse(BatchTriggerResource.RevenueRecoveryStartRequest.class.getRecordComponents()[0]
                .getName().equals("jobName"));
        assertTrue(Arrays.stream(BatchTriggerResource.RevenueRecoveryStartRequest.class.getRecordComponents())
                .noneMatch(component -> component.getName().toLowerCase().contains("sql")));
    }

    @Test
    void staleCategoryRecoveryMapsUnknownConflictMessagesSafelyAndExcludesDelivery() {
        when(recoveryService.recover(
                PracticeRevenueSourceRecoveryService.Category.PUBLICATION, OWNER))
                .thenThrow(new PracticeRevenueSourceRecoveryService.RecoveryConflictException(
                        "database says token=secret"));

        Response unknown = resource.recoverSource("publication",
                new BatchTriggerResource.StaleRecoveryRequest(true, OWNER), ACTOR);
        Response conflict = resource.recoverSource("PUBLICATION",
                new BatchTriggerResource.StaleRecoveryRequest(true, OWNER), ACTOR);
        Response wrongDeliveryShape=resource.recoverSource("DELIVERY_EVIDENCE",
                new BatchTriggerResource.StaleRecoveryRequest(true,OWNER),ACTOR);

        assertEquals(400, unknown.getStatus());
        assertEquals(new BatchTriggerResource.ErrorResponse("UNKNOWN_RECOVERY_CATEGORY"), unknown.getEntity());
        assertEquals(409, conflict.getStatus());
        assertEquals(new BatchTriggerResource.ErrorResponse("RECOVERY_PRECONDITION_FAILED"),
                conflict.getEntity());
        assertEquals(400,wrongDeliveryShape.getStatus());
        assertEquals(new BatchTriggerResource.ErrorResponse("INVALID_DELIVERY_RECOVERY_INPUT"),
                wrongDeliveryShape.getEntity());
        verify(recoveryService, never()).recover(
                eq(PracticeRevenueSourceRecoveryService.Category.COST_BASIS), anyString());
    }

    @Test
    void deliveryRetentionRecoveryAcceptsOnlyConfirmationAndNoCallerToken(){
        assertEquals(List.of("confirm"),Arrays.stream(
                BatchTriggerResource.DeliveryEvidenceRecoveryRequest.class.getRecordComponents())
                .map(component->component.getName()).toList());
        Query audit=query();
        when(em.createNativeQuery(BatchTriggerResource.AUDIT_SOURCE_RECOVERY_SQL)).thenReturn(audit);
        when(audit.executeUpdate()).thenReturn(1);
        var result=new PracticeRevenueSourceRecoveryService.RecoveryResult(
                "DELIVERY_EVIDENCE",1,"REBUILT:DELIVERY_EVIDENCE");
        when(recoveryService.recover(
                PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE,null)).thenReturn(result);

        Response response=resource.recoverDeliveryEvidence(
                new BatchTriggerResource.DeliveryEvidenceRecoveryRequest(true),ACTOR);

        assertEquals(200,response.getStatus());
        verify(recoveryService).recover(
                PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE,null);
        verify(audit).setParameter("reason","RECOVER_DELIVERY_EVIDENCE");
    }

    @Test
    void successfulCategoryRecoveryReturnsExecutionIdAndWritesOnlyClosedAuditReason() {
        Query audit = query();
        when(em.createNativeQuery(BatchTriggerResource.AUDIT_SOURCE_RECOVERY_SQL)).thenReturn(audit);
        when(audit.executeUpdate()).thenReturn(1);
        PracticeRevenueSourceRecoveryService.RecoveryResult result =
                new PracticeRevenueSourceRecoveryService.RecoveryResult("FINANCE_GL", 1, "FINANCE_GL");
        when(recoveryService.recover(
                PracticeRevenueSourceRecoveryService.Category.FINANCE_GL, OWNER)).thenReturn(result);

        Response response = resource.recoverSource("FINANCE_GL",
                new BatchTriggerResource.StaleRecoveryRequest(true, OWNER), ACTOR);

        assertEquals(200, response.getStatus());
        BatchTriggerResource.RecoveryExecutionResponse entity =
                (BatchTriggerResource.RecoveryExecutionResponse) response.getEntity();
        assertFalse(entity.executionId().isBlank());
        assertEquals(result, entity.result());
        verify(audit).setParameter("actor", ACTOR);
        verify(audit).setParameter("reason", "RECOVER_FINANCE_GL");
        assertEquals("FINANCE_GL", entity.result().recoveredIdentity());
    }

    private static Query query() {
        Query query = mock(Query.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        return query;
    }
}
