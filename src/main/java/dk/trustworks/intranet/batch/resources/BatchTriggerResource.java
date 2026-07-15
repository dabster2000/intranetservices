package dk.trustworks.intranet.batch.resources;

import dk.trustworks.intranet.aggregates.practices.services.PracticeCostBasisRefreshService;
import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueSourceRecoveryService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.batch.operations.JobOperator;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed, audited system-only control surface for the allow-listed batch/publication workflows. */
@Tag(name = "system")
@Path("/system/batch")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"system:write"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@JBossLog
public class BatchTriggerResource {

    static final String COST_JOB = "practice-cost-basis-refresh";
    static final String REVENUE_JOB = "practice-revenue-refresh";
    static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    static final Set<String> RECOVERY_CONFLICT_CODES = Set.of(
            "OWNER_LOCK_STILL_HELD", "OWNER_TOKEN_MISMATCH", "OWNER_START_TIME_UNAVAILABLE",
            "OWNER_NOT_EXPIRED", "ATTEMPT_GENERATION_MISSING", "PUBLICATION_OWNER_CHANGED",
            "COST_OWNER_NOT_FOUND", "COST_OWNER_CHANGED", "SOURCE_OWNER_CHANGED",
            "RECOVERY_LOCK_RELEASE_FAILED", "DELIVERY_RECOVERY_PRECONDITION_FAILED",
            "SOURCE_REBUILD_UNAVAILABLE", "SOURCE_REBUILD_INCOMPLETE",
            "DELIVERY_RECOVERY_CURSOR_MISSING");

    static final String COST_START_PRECONDITION_SQL = """
            SELECT COUNT(*)
            FROM practice_contribution_publication_control c
            JOIN practice_operating_cost_publication o ON o.publication_id=1
            JOIN practice_cost_basis_refresh_request r ON r.request_id=:requestId
            WHERE c.control_id=1 AND c.refresh_enabled=TRUE
              AND c.revenue_recovery_owner_token IS NULL
              AND o.latest_cost_basis_request_id=r.request_id
              AND r.request_key=:requestKey
              AND r.input_vector_fingerprint=:inputVector
              AND r.status='PENDING'
              AND NOT EXISTS (
                  SELECT 1 FROM practice_cost_basis_refresh_request newer
                  WHERE newer.request_id>r.request_id
              )
            """;
    static final String REVENUE_START_PRECONDITION_SQL = """
            SELECT COUNT(*)
            FROM practice_contribution_publication_control c
            JOIN practice_operating_cost_publication o ON o.publication_id=1
            JOIN practice_cost_basis_refresh_request r
              ON r.request_id=o.certified_cost_basis_request_id
            JOIN practice_basis_generation basis
              ON basis.generation_id=o.practice_basis_generation_id
            JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
            WHERE c.control_id=1 AND c.refresh_enabled=TRUE
              AND c.revenue_recovery_owner_token IS NULL
              AND o.refresh_state='READY' AND o.generation_at IS NOT NULL
              AND o.certified_cost_basis_request_id IS NOT NULL
              AND o.latest_cost_basis_request_id=o.certified_cost_basis_request_id
              AND o.latest_cost_basis_request_vector=o.certified_cost_basis_request_vector
              AND r.input_vector_fingerprint=o.certified_cost_basis_request_vector
              AND r.dependency_fingerprint=basis.dependency_manifest_fingerprint
              AND r.expected_full_refresh_version=b.full_refresh_version
              AND r.expected_incremental_refresh_version=b.incremental_refresh_version
              AND r.expected_finance_gl_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='FINANCE_GL' AND source_state='READY'
              )
              AND r.expected_account_classification_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='ACCOUNT_CLASSIFICATION' AND source_state='READY'
              )
              AND r.expected_practice_basis_input_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='PRACTICE_BASIS_INPUT' AND source_state='READY'
              )
              AND r.status IN ('READY','NO_CHANGE')
              AND ((r.status='READY' AND r.resulting_cost_generation_at=o.generation_at)
                   OR (r.status='NO_CHANGE' AND r.compared_cost_generation_at=o.generation_at))
              AND NOT EXISTS (
                  SELECT 1 FROM practice_revenue_source_watermark w
                  WHERE w.source_state <> 'READY'
              )
              AND (SELECT COUNT(*) FROM practice_revenue_source_watermark)=9
              AND NOT EXISTS (
                  SELECT 1 FROM practice_cost_basis_refresh_request newer
                  WHERE newer.request_id>r.request_id
              )
            """;
    static final String ENABLE_BUILD_SQL = """
            UPDATE practice_contribution_publication_control
            SET refresh_enabled=TRUE, control_version=control_version+1,
                last_transition_actor=:actor, last_transition_at=UTC_TIMESTAMP(6),
                last_transition_reason='ENABLE_BUILD'
            WHERE control_id=1 AND refresh_enabled=FALSE
              AND revenue_recovery_owner_token IS NULL
            """;
    static final String ENABLE_SERVING_SQL = """
            UPDATE practice_contribution_publication_control c
            JOIN practice_revenue_publication p ON p.publication_key='PRACTICE_CONTRIBUTION'
            JOIN practice_operating_cost_publication o ON o.publication_id=1
            JOIN practice_cost_basis_refresh_request req
              ON req.request_id=o.certified_cost_basis_request_id
            JOIN practice_basis_generation basis
              ON basis.generation_id=o.practice_basis_generation_id
            JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
            SET c.contribution_serving_enabled=TRUE, c.control_version=c.control_version+1,
                c.last_transition_actor=:actor, c.last_transition_at=UTC_TIMESTAMP(6),
                c.last_transition_reason='ENABLE_CONTRIBUTION_SERVING'
            WHERE c.control_id=1 AND c.contribution_serving_enabled=FALSE
              AND c.revenue_recovery_owner_token IS NULL
              AND p.status='READY' AND p.published_generation_id=:generation
              AND p.paired_cost_generation_at=:costGeneration
              AND o.refresh_state='READY' AND o.generation_at=:costGeneration
              AND p.practice_basis_generation_id=o.practice_basis_generation_id
              AND p.full_bi_refresh_version=b.full_refresh_version
              AND o.latest_cost_basis_request_id=o.certified_cost_basis_request_id
              AND o.latest_cost_basis_request_vector=o.certified_cost_basis_request_vector
              AND req.input_vector_fingerprint=o.certified_cost_basis_request_vector
              AND req.dependency_fingerprint=basis.dependency_manifest_fingerprint
              AND req.expected_full_refresh_version=b.full_refresh_version
              AND req.expected_incremental_refresh_version=b.incremental_refresh_version
              AND req.expected_finance_gl_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='FINANCE_GL' AND source_state='READY'
              )
              AND req.expected_account_classification_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='ACCOUNT_CLASSIFICATION' AND source_state='READY'
              )
              AND req.expected_practice_basis_input_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='PRACTICE_BASIS_INPUT' AND source_state='READY'
              )
              AND req.status IN ('READY','NO_CHANGE')
              AND ((req.status='READY' AND req.resulting_cost_generation_at=o.generation_at)
                   OR (req.status='NO_CHANGE' AND req.compared_cost_generation_at=o.generation_at))
              AND (SELECT COUNT(*) FROM practice_revenue_source_watermark)=9
              AND NOT EXISTS (
                  SELECT 1 FROM practice_revenue_source_watermark w
                  WHERE w.source_state <> 'READY'
                     OR w.source_version <> CASE w.source_name
                         WHEN 'INVOICE_DOCUMENT' THEN p.invoice_document_source_version
                         WHEN 'FINANCE_GL' THEN p.finance_gl_source_version
                         WHEN 'CURRENCY' THEN p.currency_source_version
                         WHEN 'ACCOUNT_CLASSIFICATION' THEN p.account_classification_source_version
                         WHEN 'INVOICE_ATTRIBUTION' THEN p.invoice_attribution_source_version
                         WHEN 'SELF_BILLED' THEN p.self_billed_source_version
                         WHEN 'PHANTOM_ATTRIBUTION' THEN p.phantom_attribution_source_version
                         WHEN 'DELIVERY_EVIDENCE' THEN p.delivery_evidence_source_version
                         WHEN 'PRACTICE_BASIS_INPUT' THEN p.practice_basis_input_source_version
                         ELSE NULL
                     END
              )
              AND NOT EXISTS (
                  SELECT 1 FROM practice_cost_basis_refresh_request newer
                  WHERE newer.request_id>req.request_id
              )
            """;
    static final String COMPLETE_RECOVERY_AND_ENABLE_SERVING_SQL = """
            UPDATE practice_contribution_publication_control c
            JOIN practice_revenue_publication p ON p.publication_key='PRACTICE_CONTRIBUTION'
            JOIN practice_operating_cost_publication o ON o.publication_id=1
            JOIN practice_cost_basis_refresh_request req
              ON req.request_id=o.certified_cost_basis_request_id
            JOIN practice_basis_generation basis
              ON basis.generation_id=o.practice_basis_generation_id
            JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
            SET c.refresh_enabled=TRUE, c.contribution_serving_enabled=TRUE,
                c.control_version=c.control_version+1,
                c.last_transition_actor=:actor, c.last_transition_at=UTC_TIMESTAMP(6),
                c.last_transition_reason='COMPLETE_RECOVERY_ENABLE_SERVING',
                c.revenue_recovery_execution_id=NULL, c.revenue_recovery_owner_token=NULL,
                c.revenue_recovery_started_at=NULL, c.revenue_recovery_state=NULL,
                c.recovery_expected_cost_generation_at=NULL,
                c.recovery_expected_cost_request_id=NULL,
                c.recovery_expected_cost_request_key=NULL,
                c.recovery_expected_cost_input_vector_fingerprint=NULL,
                c.recovery_expected_full_refresh_version=NULL,
                c.recovery_expected_source_vector_fingerprint=NULL
            WHERE c.control_id=1 AND c.contribution_serving_enabled=FALSE
              AND c.revenue_recovery_state='BUILT'
              AND c.revenue_recovery_execution_id=:recoveryExecution
              AND p.status='READY' AND p.published_generation_id=:generation
              AND p.paired_cost_generation_at=:costGeneration
              AND o.refresh_state='READY' AND o.generation_at=:costGeneration
              AND p.practice_basis_generation_id=o.practice_basis_generation_id
              AND p.full_bi_refresh_version=b.full_refresh_version
              AND o.latest_cost_basis_request_id=o.certified_cost_basis_request_id
              AND o.latest_cost_basis_request_vector=o.certified_cost_basis_request_vector
              AND req.input_vector_fingerprint=o.certified_cost_basis_request_vector
              AND req.dependency_fingerprint=basis.dependency_manifest_fingerprint
              AND req.expected_full_refresh_version=b.full_refresh_version
              AND req.expected_incremental_refresh_version=b.incremental_refresh_version
              AND req.expected_finance_gl_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='FINANCE_GL' AND source_state='READY'
              )
              AND req.expected_account_classification_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='ACCOUNT_CLASSIFICATION' AND source_state='READY'
              )
              AND req.expected_practice_basis_input_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='PRACTICE_BASIS_INPUT' AND source_state='READY'
              )
              AND req.request_id=c.recovery_expected_cost_request_id
              AND req.request_key=c.recovery_expected_cost_request_key
              AND req.input_vector_fingerprint=c.recovery_expected_cost_input_vector_fingerprint
              AND req.expected_full_refresh_version=c.recovery_expected_full_refresh_version
              AND req.status IN ('READY','NO_CHANGE')
              AND ((req.status='READY' AND req.resulting_cost_generation_at=o.generation_at)
                   OR (req.status='NO_CHANGE' AND req.compared_cost_generation_at=o.generation_at))
              AND c.recovery_expected_source_vector_fingerprint=(
                  SELECT SHA2(GROUP_CONCAT(CONCAT(w.source_name,'=',w.source_version)
                                           ORDER BY w.source_name SEPARATOR '|'),256)
                  FROM practice_revenue_source_watermark w
                  HAVING COUNT(*)=9 AND SUM(w.source_state <> 'READY')=0
              )
              AND (SELECT COUNT(*) FROM practice_revenue_source_watermark)=9
              AND NOT EXISTS (
                  SELECT 1 FROM practice_revenue_source_watermark w
                  WHERE w.source_state <> 'READY'
                     OR w.source_version <> CASE w.source_name
                         WHEN 'INVOICE_DOCUMENT' THEN p.invoice_document_source_version
                         WHEN 'FINANCE_GL' THEN p.finance_gl_source_version
                         WHEN 'CURRENCY' THEN p.currency_source_version
                         WHEN 'ACCOUNT_CLASSIFICATION' THEN p.account_classification_source_version
                         WHEN 'INVOICE_ATTRIBUTION' THEN p.invoice_attribution_source_version
                         WHEN 'SELF_BILLED' THEN p.self_billed_source_version
                         WHEN 'PHANTOM_ATTRIBUTION' THEN p.phantom_attribution_source_version
                         WHEN 'DELIVERY_EVIDENCE' THEN p.delivery_evidence_source_version
                         WHEN 'PRACTICE_BASIS_INPUT' THEN p.practice_basis_input_source_version
                         ELSE NULL
                     END
              )
              AND NOT EXISTS (
                  SELECT 1 FROM practice_cost_basis_refresh_request newer
                  WHERE newer.request_id>req.request_id
              )
            """;
    static final String DISABLE_CONTRIBUTION_SQL = """
            UPDATE practice_contribution_publication_control
            SET refresh_enabled=FALSE, contribution_serving_enabled=FALSE,
                control_version=control_version+1,
                last_transition_actor=:actor, last_transition_at=UTC_TIMESTAMP(6),
                last_transition_reason='DISABLE_CONTRIBUTION'
            WHERE control_id=1 AND revenue_recovery_owner_token IS NULL
              AND (refresh_enabled=TRUE OR contribution_serving_enabled=TRUE)
            """;
    static final String CLEAR_BUILT_RECOVERY_SQL = """
            UPDATE practice_contribution_publication_control
            SET refresh_enabled=FALSE, contribution_serving_enabled=FALSE,
                control_version=control_version+1,
                last_transition_actor=:actor, last_transition_at=UTC_TIMESTAMP(6),
                last_transition_reason='CLEAR_BUILT_RECOVERY_WITHOUT_SERVING',
                revenue_recovery_execution_id=NULL, revenue_recovery_owner_token=NULL,
                revenue_recovery_started_at=NULL, revenue_recovery_state=NULL,
                recovery_expected_cost_generation_at=NULL,
                recovery_expected_cost_request_id=NULL,
                recovery_expected_cost_request_key=NULL,
                recovery_expected_cost_input_vector_fingerprint=NULL,
                recovery_expected_full_refresh_version=NULL,
                recovery_expected_source_vector_fingerprint=NULL
            WHERE control_id=1 AND revenue_recovery_state='BUILT'
              AND revenue_recovery_execution_id=:recoveryExecution
            """;
    static final String DISABLE_COST_SERVING_SQL = """
            UPDATE practice_contribution_publication_control
            SET refresh_enabled=FALSE, contribution_serving_enabled=FALSE,
                legacy_cost_serving_enabled=FALSE, control_version=control_version+1,
                last_transition_actor=:actor, last_transition_at=UTC_TIMESTAMP(6),
                last_transition_reason='DISABLE_COST_SERVING'
            WHERE control_id=1 AND revenue_recovery_owner_token IS NULL
              AND (refresh_enabled=TRUE OR contribution_serving_enabled=TRUE
                   OR legacy_cost_serving_enabled=TRUE)
            """;
    static final String ENABLE_COST_SERVING_SQL = """
            UPDATE practice_contribution_publication_control c
            JOIN practice_operating_cost_publication o ON o.publication_id=1
            JOIN practice_cost_basis_refresh_request r
              ON r.request_id=o.certified_cost_basis_request_id
            JOIN practice_basis_generation basis
              ON basis.generation_id=o.practice_basis_generation_id
            JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
            SET c.legacy_cost_serving_enabled=TRUE, c.control_version=c.control_version+1,
                c.last_transition_actor=:actor, c.last_transition_at=UTC_TIMESTAMP(6),
                c.last_transition_reason='ENABLE_COST_SERVING'
            WHERE c.control_id=1 AND c.legacy_cost_serving_enabled=FALSE
              AND c.contribution_serving_enabled=FALSE
              AND c.revenue_recovery_owner_token IS NULL
              AND o.refresh_state='READY' AND o.generation_at=:costGeneration
              AND o.latest_cost_basis_request_id=o.certified_cost_basis_request_id
              AND o.latest_cost_basis_request_vector=o.certified_cost_basis_request_vector
              AND r.input_vector_fingerprint=o.certified_cost_basis_request_vector
              AND r.dependency_fingerprint=basis.dependency_manifest_fingerprint
              AND r.expected_full_refresh_version=b.full_refresh_version
              AND r.expected_incremental_refresh_version=b.incremental_refresh_version
              AND r.expected_finance_gl_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='FINANCE_GL' AND source_state='READY'
              )
              AND r.expected_account_classification_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='ACCOUNT_CLASSIFICATION' AND source_state='READY'
              )
              AND r.expected_practice_basis_input_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='PRACTICE_BASIS_INPUT' AND source_state='READY'
              )
              AND r.status IN ('READY','NO_CHANGE')
              AND ((r.status='READY' AND r.resulting_cost_generation_at=o.generation_at)
                   OR (r.status='NO_CHANGE' AND r.compared_cost_generation_at=o.generation_at))
              AND NOT EXISTS (
                  SELECT 1 FROM practice_cost_basis_refresh_request newer
                  WHERE newer.request_id>r.request_id
              )
            """;
    static final String RECOVERY_START_SQL = """
            UPDATE practice_contribution_publication_control c
            JOIN practice_operating_cost_publication o ON o.publication_id=1
            JOIN practice_cost_basis_refresh_request r ON r.request_id=:requestId
            JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
            JOIN practice_basis_generation basis
              ON basis.generation_id=o.practice_basis_generation_id
            SET c.revenue_recovery_execution_id=:execution,
                c.revenue_recovery_owner_token=:owner,
                c.revenue_recovery_started_at=UTC_TIMESTAMP(6),
                c.revenue_recovery_state='RUNNING',
                c.recovery_expected_cost_generation_at=:costGeneration,
                c.recovery_expected_cost_request_id=:requestId,
                c.recovery_expected_cost_request_key=:requestKey,
                c.recovery_expected_cost_input_vector_fingerprint=:inputVector,
                c.recovery_expected_full_refresh_version=r.expected_full_refresh_version,
                c.recovery_expected_source_vector_fingerprint=:sourceVector,
                c.control_version=c.control_version+1,
                c.last_transition_actor=:actor, c.last_transition_at=UTC_TIMESTAMP(6),
                c.last_transition_reason='START_REVENUE_ONLY_RECOVERY'
            WHERE c.control_id=1 AND c.refresh_enabled=FALSE
              AND c.contribution_serving_enabled=FALSE AND c.legacy_cost_serving_enabled=TRUE
              AND c.revenue_recovery_owner_token IS NULL
              AND o.refresh_state='READY' AND o.generation_at=:costGeneration
              AND o.latest_cost_basis_request_id=:requestId
              AND o.certified_cost_basis_request_id=:requestId
              AND o.latest_cost_basis_request_vector=:inputVector
              AND o.certified_cost_basis_request_vector=:inputVector
              AND r.request_key=:requestKey AND r.input_vector_fingerprint=:inputVector
              AND r.dependency_fingerprint=basis.dependency_manifest_fingerprint
              AND r.expected_full_refresh_version=b.full_refresh_version
              AND r.expected_incremental_refresh_version=b.incremental_refresh_version
              AND r.expected_finance_gl_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='FINANCE_GL' AND source_state='READY'
              )
              AND r.expected_account_classification_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='ACCOUNT_CLASSIFICATION' AND source_state='READY'
              )
              AND r.expected_practice_basis_input_version=(
                  SELECT source_version FROM practice_revenue_source_watermark
                  WHERE source_name='PRACTICE_BASIS_INPUT' AND source_state='READY'
              )
              AND r.status IN ('READY','NO_CHANGE')
              AND ((r.status='READY' AND r.resulting_cost_generation_at=o.generation_at)
                   OR (r.status='NO_CHANGE' AND r.compared_cost_generation_at=o.generation_at))
              AND b.full_refresh_version=r.expected_full_refresh_version
              AND :sourceVector=(
                  SELECT SHA2(GROUP_CONCAT(CONCAT(w.source_name,'=',w.source_version)
                                           ORDER BY w.source_name SEPARATOR '|'),256)
                  FROM practice_revenue_source_watermark w
                  HAVING COUNT(*)=9 AND SUM(w.source_state <> 'READY')=0
              )
              AND NOT EXISTS (
                  SELECT 1 FROM practice_cost_basis_refresh_request newer
                  WHERE newer.request_id>:requestId
              )
            """;
    static final String AUDIT_SOURCE_RECOVERY_SQL = """
            UPDATE practice_contribution_publication_control
            SET control_version=control_version+1,
                last_transition_actor=:actor, last_transition_at=UTC_TIMESTAMP(6),
                last_transition_reason=:reason
            WHERE control_id=1
            """;

    @Inject
    JobOperator jobOperator;

    @Inject
    EntityManager em;

    @Inject
    PracticeRevenueSourceRecoveryService recoveryService;

    /** Retains the two existing finance starts but maps path values through a closed typed enum. */
    @POST
    @Path("/{action}/start")
    public Response start(@PathParam("action") String action) {
        FinanceBatchAction parsed;
        try {
            parsed = FinanceBatchAction.fromPath(action);
        } catch (IllegalArgumentException e) {
            return badRequest("UNKNOWN_BATCH_ACTION");
        }
        return startJob(parsed.jobName(), parsed.path());
    }

    @POST
    @Path("/practice-cost-basis-refresh/start")
    @Transactional
    public Response startCostBasis(CostBasisStartRequest request,
                                   @HeaderParam("X-Requested-By") String actor) {
        if (!validActor(actor) || request == null || !request.confirm()
                || request.expectedRequestId() == null || request.expectedRequestId().signum() <= 0
                || !validFingerprint(request.expectedRequestKey())
                || !validFingerprint(request.expectedInputVectorFingerprint())) {
            return badRequest("INVALID_COST_BASIS_START_INPUT");
        }
        Number eligible = (Number) em.createNativeQuery(COST_START_PRECONDITION_SQL)
                .setParameter("requestId", request.expectedRequestId())
                .setParameter("requestKey", request.expectedRequestKey())
                .setParameter("inputVector", request.expectedInputVectorFingerprint())
                .getSingleResult();
        if (eligible.longValue() != 1) return conflict("COST_BASIS_START_PRECONDITION_FAILED");
        if (isRunning(COST_JOB)) return conflict("JOB_ALREADY_RUNNING");
        Properties parameters = new PracticeCostBasisRefreshService.ExpectedRequest(
                request.expectedRequestId(), request.expectedRequestKey(),
                request.expectedInputVectorFingerprint()).toJobProperties();
        long executionId = jobOperator.start(COST_JOB, parameters);
        return Response.ok(new CostBasisExecutionResponse(
                COST_JOB, Long.toString(executionId), executionId, request.expectedRequestId())).build();
    }

    @POST
    @Path("/practice-revenue-refresh/start")
    @Transactional
    public Response startRevenue(ConfirmedAction request,
                                 @HeaderParam("X-Requested-By") String actor) {
        if (!validActor(actor) || request == null || !request.confirm()) {
            return badRequest("INVALID_REVENUE_START_INPUT");
        }
        Number eligible = (Number) em.createNativeQuery(REVENUE_START_PRECONDITION_SQL).getSingleResult();
        if (eligible.longValue() != 1) return conflict("REVENUE_START_PRECONDITION_FAILED");
        return startJob(REVENUE_JOB, REVENUE_JOB);
    }

    @POST
    @Path("/practice-revenue-refresh/recovery-start")
    @Transactional
    public Response startRevenueRecovery(RevenueRecoveryStartRequest request,
                                         @HeaderParam("X-Requested-By") String actor) {
        if (!validActor(actor) || !validRecoveryStart(request)) {
            return badRequest("INVALID_REVENUE_RECOVERY_INPUT");
        }
        if (isRunning(REVENUE_JOB)) return conflict("REVENUE_REFRESH_ALREADY_RUNNING");
        LocalDateTime costGeneration = parseInstant(request.expectedCostGenerationAt());
        String execution = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        int updated = em.createNativeQuery(RECOVERY_START_SQL)
                .setParameter("execution", execution)
                .setParameter("owner", owner)
                .setParameter("costGeneration", costGeneration)
                .setParameter("requestId", request.expectedCostRequestId())
                .setParameter("requestKey", request.expectedCostRequestKey())
                .setParameter("inputVector", request.expectedCostInputVectorFingerprint())
                .setParameter("sourceVector", request.expectedSourceVectorFingerprint())
                .setParameter("actor", actor)
                .executeUpdate();
        if (updated != 1) return conflict("REVENUE_RECOVERY_PRECONDITION_FAILED");
        Properties parameters = new Properties();
        parameters.setProperty("recoveryExecutionId", execution);
        parameters.setProperty("recoveryOwnerToken", owner);
        long batchExecution = jobOperator.start(REVENUE_JOB, parameters);
        return Response.ok(new ExecutionResponse("REVENUE_RECOVERY", execution, batchExecution)).build();
    }

    @POST
    @Path("/practice-revenue-publication/enable-build")
    @Transactional
    public Response enableBuild(ConfirmedAction request,
                                @HeaderParam("X-Requested-By") String actor) {
        if (!confirmed(request, actor)) return badRequest("EXPLICIT_CONFIRMATION_REQUIRED");
        int updated = em.createNativeQuery(ENABLE_BUILD_SQL).setParameter("actor", actor).executeUpdate();
        return transition("ENABLE_BUILD", updated);
    }

    @POST
    @Path("/practice-revenue-publication/enable-serving")
    @Transactional
    public Response enableContributionServing(ContributionServingRequest request,
                                              @HeaderParam("X-Requested-By") String actor) {
        if (!validServingRequest(request, actor)) return badRequest("INVALID_ENABLE_SERVING_INPUT");
        LocalDateTime costGeneration = parseInstant(request.expectedCostGenerationAt());
        String sql = request.expectedRecoveryExecutionId() == null
                ? ENABLE_SERVING_SQL : COMPLETE_RECOVERY_AND_ENABLE_SERVING_SQL;
        var query = em.createNativeQuery(sql)
                .setParameter("actor", actor)
                .setParameter("generation", request.expectedGenerationId())
                .setParameter("costGeneration", costGeneration);
        if (request.expectedRecoveryExecutionId() != null) {
            query.setParameter("recoveryExecution", request.expectedRecoveryExecutionId());
        }
        return transition("ENABLE_CONTRIBUTION_SERVING", query.executeUpdate());
    }

    @POST
    @Path("/practice-revenue-publication/disable")
    @Transactional
    public Response disableContribution(ContributionDisableRequest request,
                                        @HeaderParam("X-Requested-By") String actor) {
        if (request == null || !request.confirm() || !validActor(actor)) {
            return badRequest("EXPLICIT_CONFIRMATION_REQUIRED");
        }
        var query = em.createNativeQuery(request.expectedRecoveryExecutionId() == null
                        ? DISABLE_CONTRIBUTION_SQL : CLEAR_BUILT_RECOVERY_SQL)
                .setParameter("actor", actor);
        if (request.expectedRecoveryExecutionId() != null) {
            if (!canonicalUuid(request.expectedRecoveryExecutionId())) {
                return badRequest("INVALID_RECOVERY_EXECUTION_ID");
            }
            query.setParameter("recoveryExecution", request.expectedRecoveryExecutionId());
        }
        return transition("DISABLE_CONTRIBUTION", query.executeUpdate());
    }

    @POST
    @Path("/practice-cost-basis-publication/disable-serving")
    @Transactional
    public Response disableCostServing(ConfirmedAction request,
                                       @HeaderParam("X-Requested-By") String actor) {
        if (!confirmed(request, actor)) return badRequest("EXPLICIT_CONFIRMATION_REQUIRED");
        int updated = em.createNativeQuery(DISABLE_COST_SERVING_SQL)
                .setParameter("actor", actor).executeUpdate();
        return transition("DISABLE_COST_SERVING", updated);
    }

    @POST
    @Path("/practice-cost-basis-publication/enable-serving")
    @Transactional
    public Response enableCostServing(CostServingRequest request,
                                      @HeaderParam("X-Requested-By") String actor) {
        if (request == null || !request.confirm() || !validActor(actor)
                || parseInstant(request.expectedCostGenerationAt()) == null) {
            return badRequest("INVALID_ENABLE_COST_SERVING_INPUT");
        }
        int updated = em.createNativeQuery(ENABLE_COST_SERVING_SQL)
                .setParameter("actor", actor)
                .setParameter("costGeneration", parseInstant(request.expectedCostGenerationAt()))
                .executeUpdate();
        return transition("ENABLE_COST_SERVING", updated);
    }

    @POST
    @Path("/practice-revenue-source-recovery/DELIVERY_EVIDENCE/start")
    @Transactional
    public Response recoverDeliveryEvidence(DeliveryEvidenceRecoveryRequest request,
                                            @HeaderParam("X-Requested-By") String actor) {
        if(request==null||!request.confirm()||!validActor(actor)){
            return badRequest("INVALID_DELIVERY_RECOVERY_INPUT");
        }
        return executeSourceRecovery(PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE,
                null,actor);
    }

    @POST
    @Path("/practice-revenue-source-recovery/{category}/start")
    @Transactional
    public Response recoverSource(@PathParam("category") String category,
                                  StaleRecoveryRequest request,
                                  @HeaderParam("X-Requested-By") String actor) {
        if (request == null || !request.confirm() || !validActor(actor)) {
            return badRequest("INVALID_STALE_RECOVERY_INPUT");
        }
        PracticeRevenueSourceRecoveryService.Category parsed;
        try {
            parsed = PracticeRevenueSourceRecoveryService.Category.fromPath(category);
        } catch (IllegalArgumentException e) {
            return badRequest("UNKNOWN_RECOVERY_CATEGORY");
        }
        if(parsed==PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE){
            return badRequest("INVALID_DELIVERY_RECOVERY_INPUT");
        }
        return executeSourceRecovery(parsed,request.expectedOwnerToken(),actor);
    }

    private Response executeSourceRecovery(PracticeRevenueSourceRecoveryService.Category parsed,
                                           String expectedOwnerToken,String actor){
        try {
            PracticeRevenueSourceRecoveryService.RecoveryResult result =
                    recoveryService.recover(parsed, expectedOwnerToken);
            em.createNativeQuery(AUDIT_SOURCE_RECOVERY_SQL)
                    .setParameter("actor", actor)
                    .setParameter("reason", "RECOVER_" + parsed.name())
                    .executeUpdate();
            String execution = UUID.randomUUID().toString();
            return Response.ok(new RecoveryExecutionResponse(execution, result)).build();
        } catch (PracticeRevenueSourceRecoveryService.RecoveryConflictException e) {
            return conflict(RECOVERY_CONFLICT_CODES.contains(e.getMessage())
                    ? e.getMessage() : "RECOVERY_PRECONDITION_FAILED");
        } catch (IllegalArgumentException e) {
            return badRequest(parsed==PracticeRevenueSourceRecoveryService.Category.DELIVERY_EVIDENCE
                    ?"INVALID_DELIVERY_RECOVERY_INPUT":"INVALID_STALE_RECOVERY_INPUT");
        }
    }

    private Response startJob(String jobName, String action) {
        if (isRunning(jobName)) return conflict("JOB_ALREADY_RUNNING");
        long executionId = jobOperator.start(jobName, new Properties());
        log.infof("Started closed batch action %s executionId=%d", action, executionId);
        return Response.ok(new ExecutionResponse(action, Long.toString(executionId), executionId)).build();
    }

    private boolean isRunning(String jobName) {
        Set<String> names = jobOperator.getJobNames();
        return names != null && names.contains(jobName) && !jobOperator.getRunningExecutions(jobName).isEmpty();
    }

    private static Response transition(String action, int updated) {
        if (updated != 1) return conflict(action + "_PRECONDITION_FAILED");
        return Response.ok(new ExecutionResponse(action, UUID.randomUUID().toString(), null)).build();
    }

    private static boolean confirmed(ConfirmedAction request, String actor) {
        return request != null && request.confirm() && validActor(actor);
    }

    private static boolean validServingRequest(ContributionServingRequest request, String actor) {
        return request != null && request.confirm() && validActor(actor)
                && canonicalUuid(request.expectedGenerationId())
                && parseInstant(request.expectedCostGenerationAt()) != null
                && (request.expectedRecoveryExecutionId() == null
                    || canonicalUuid(request.expectedRecoveryExecutionId()));
    }

    private static boolean validRecoveryStart(RevenueRecoveryStartRequest request) {
        return request != null && request.confirm()
                && parseInstant(request.expectedCostGenerationAt()) != null
                && request.expectedCostRequestId() != null
                && request.expectedCostRequestId().signum() > 0
                && validFingerprint(request.expectedCostRequestKey())
                && validFingerprint(request.expectedCostInputVectorFingerprint())
                && validFingerprint(request.expectedSourceVectorFingerprint());
    }

    private static boolean validActor(String actor) {
        return canonicalUuid(actor);
    }

    private static boolean validFingerprint(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private static boolean canonicalUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static LocalDateTime parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Response badRequest(String code) {
        return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(code)).build();
    }

    private static Response conflict(String code) {
        return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(code)).build();
    }

    enum FinanceBatchAction {
        FINANCE_LOAD_ECONOMICS("finance-load-economics"),
        FINANCE_INVOICE_SYNC("finance-invoice-sync");

        private final String path;

        FinanceBatchAction(String path) {
            this.path = path;
        }

        String path() {
            return path;
        }

        String jobName() {
            return path;
        }

        static FinanceBatchAction fromPath(String value) {
            for (FinanceBatchAction action : values()) if (action.path.equals(value)) return action;
            throw new IllegalArgumentException("unknown batch action");
        }
    }

    public record ConfirmedAction(boolean confirm) {
    }

    public record CostBasisStartRequest(boolean confirm, BigInteger expectedRequestId,
                                        String expectedRequestKey,
                                        String expectedInputVectorFingerprint) {
    }

    public record RevenueRecoveryStartRequest(boolean confirm, String expectedCostGenerationAt,
                                              BigInteger expectedCostRequestId,
                                              String expectedCostRequestKey,
                                              String expectedCostInputVectorFingerprint,
                                              String expectedSourceVectorFingerprint) {
    }

    public record ContributionServingRequest(boolean confirm, String expectedGenerationId,
                                             String expectedCostGenerationAt,
                                             String expectedRecoveryExecutionId) {
    }

    public record ContributionDisableRequest(boolean confirm, String expectedRecoveryExecutionId) {
    }

    public record CostServingRequest(boolean confirm, String expectedCostGenerationAt) {
    }

    public record StaleRecoveryRequest(boolean confirm, String expectedOwnerToken) {
    }

    /** Closed retention-gap input: no token, job name, SQL, or arbitrary command is accepted. */
    public record DeliveryEvidenceRecoveryRequest(boolean confirm) {
    }

    public record ExecutionResponse(String action, String executionId, Long batchExecutionId) {
    }

    public record CostBasisExecutionResponse(String action, String executionId, Long batchExecutionId,
                                             BigInteger requestId) {
    }

    public record RecoveryExecutionResponse(String executionId,
                                            PracticeRevenueSourceRecoveryService.RecoveryResult result) {
    }

    public record ErrorResponse(String code) {
    }
}
