package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Transaction-local source invalidation and cost-first routing. */
@ApplicationScoped
public class PracticeRevenueDirtyMarker {
    @Inject EntityManager em;

    /**
     * Self client-proxy. The ordinary poll orchestrates two independent {@code REQUIRES_NEW}
     * transactions (settle, then advance); calling them through the proxy makes each start its own
     * transaction (a direct {@code this.} call would bypass the interceptor and share one transaction).
     */
    @Inject PracticeRevenueDirtyMarker self;

    @Transactional
    public void mark(Source source, YearMonth affectedMonth) {
        em.createNativeQuery("CALL sp_mark_practice_revenue_source_changed(:source, :month)")
                .setParameter("source", source.name())
                .setParameter("month", affectedMonth == null ? null : affectedMonth.atDay(1))
                .executeUpdate();
        if (source.requiresCostFirst()) enqueueCostFirst(source, "DIRTY_MARKER");
    }

    @Transactional
    public void markDependency(Source source, String dependencyKind, String dependencyKey) {
        if (dependencyKind == null || dependencyKind.isBlank()
                || dependencyKey == null || dependencyKey.isBlank()) {
            throw new IllegalArgumentException("closed dependency kind and key are required");
        }
        em.createNativeQuery("CALL sp_mark_practice_revenue_dependency_changed(:source, :kind, :key)")
                .setParameter("source", source.name()).setParameter("kind", dependencyKind)
                .setParameter("key", dependencyKey).executeUpdate();
    }

    /**
     * Consumes the shared BI change log for the currently published delivery dependency graph.
     *
     * <p>Two short {@code REQUIRES_NEW} transactions keep this off the hot INSERT path (see
     * {@link DeliveryEvidencePoll}): TX1 ({@link #settleDeliveryHorizon()}) settles a commit-order-safe
     * horizon under a bounded {@code fact_change_log} lock and commits immediately; TX2
     * ({@link #advanceDeliveryCursor}/{@link #resolveDeliveryWatermark()}) computes relevance
     * non-locking and advances the cursor under a watermark-only CAS. No lock is held across the heavy
     * bounds join, and neither transaction ever holds the log while waiting for the watermark, so the
     * poll cannot invert against the V412 contract-consultant triggers. A running revenue attempt owns
     * the final union scan, so TX1 defers instead of advancing while an attempt is RUNNING.
     */
    public DeliveryPollResult pollDeliveryEvidence() {
        return DeliveryEvidencePoll.poll(new DeliveryEvidencePoll.DeliveryPollTransactions() {
            @Override
            public DeliveryEvidencePoll.SettleOutcome settle() {
                return self.settleDeliveryHorizon();
            }

            @Override
            public DeliveryPollResult resolveWatermarkOnly() {
                return self.resolveDeliveryWatermark();
            }

            @Override
            public DeliveryPollResult advance(BigInteger cursor, BigInteger settledTarget) {
                return self.advanceDeliveryCursor(cursor, settledTarget);
            }
        });
    }

    /** TX1: publication guard plus the bounded settle scan. Short; holds no lock across any join. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public DeliveryEvidencePoll.SettleOutcome settleDeliveryHorizon() {
        return DeliveryEvidencePoll.settle(new EntityManagerDeliveryPollGateway(em));
    }

    /** TX2: watermark-only retention-gap/defer resolution for a non-advanceable snapshot. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public DeliveryPollResult resolveDeliveryWatermark() {
        return DeliveryEvidencePoll.resolveWatermarkOnly(new EntityManagerDeliveryPollGateway(em));
    }

    /** TX2: non-locking bounds, then a watermark-only CAS advance over the settled range. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public DeliveryPollResult advanceDeliveryCursor(BigInteger cursor, BigInteger settledTarget) {
        return DeliveryEvidencePoll.advance(new EntityManagerDeliveryPollGateway(em), cursor, settledTarget);
    }

    /** Final current-read scan over published plus candidate dependencies. Caller owns the attempt. */
    @Transactional(Transactional.TxType.MANDATORY)
    public DeliveryPollResult finalDeliveryEvidenceScan(String attemptGenerationId) {
        if (attemptGenerationId == null || attemptGenerationId.isBlank()) {
            throw new IllegalArgumentException("attempt generation is required");
        }
        return scanFinalDeliveryUnion(attemptGenerationId);
    }

    /**
     * Attempt-owned final union scan over the published plus attempt dependency generations.
     *
     * <p>Lock order is {@code fact_change_log → watermark} to match the V412 contract-consultant
     * triggers (which insert the log row then update the watermark); acquiring the watermark first
     * inverted against them and could deadlock. The cursor is therefore read non-locking, the log
     * range is locked, and then the watermark is X-locked and the cursor re-verified unchanged: the
     * poll defers while an attempt is RUNNING, so the cursor is stable during a build, and any
     * unexpected move aborts the attempt for retry. The shared cursor/retention/advance SQL is reused
     * from {@link DeliveryEvidencePoll}; only the union bounds query is specific to this scan.
     */
    private DeliveryPollResult scanFinalDeliveryUnion(String attemptGenerationId) {
        Object[] snapshot = (Object[]) em.createNativeQuery(DeliveryEvidencePoll.POLL_WATERMARK_SNAPSHOT_SQL)
                .getSingleResult();
        BigInteger cursorSnapshot = integer(snapshot[0]);

        // Log range first (matching the trigger writers), so the watermark lock below cannot invert.
        @SuppressWarnings("unchecked")
        List<Object> currentRows = em.createNativeQuery(DeliveryEvidencePoll.LOG_HORIZON_LOCK_SQL)
                .setParameter("cursor", cursorSnapshot).getResultList();

        @SuppressWarnings("unchecked")
        List<Object[]> watermarkRows = em.createNativeQuery(DeliveryEvidencePoll.WATERMARK_LOCK_SQL)
                .getResultList();
        if (watermarkRows.size() != 1) {
            throw new WatermarkConflictException("DELIVERY_EVIDENCE_WATERMARK_MISSING");
        }
        Object[] watermark = watermarkRows.getFirst();
        BigInteger cursor = integer(watermark[0]);
        BigInteger pruned = integer(watermark[1]);
        if (cursor.compareTo(cursorSnapshot) != 0) {
            // The cursor moved between the non-locking read and the lock; abort so the attempt retries.
            throw new WatermarkConflictException("DELIVERY_EVIDENCE_CURSOR_CONFLICT");
        }
        if (cursor.compareTo(pruned) < 0) {
            if ("FAILED".equals(String.valueOf(watermark[2]))
                    && "FACT_CHANGE_LOG_RETENTION_GAP".equals(value(watermark[4]))) {
                return DeliveryPollResult.deferred(cursor);
            }
            int failed = em.createNativeQuery(DeliveryEvidencePoll.RETENTION_FAIL_SQL)
                    .setParameter("cursor", cursor).setParameter("pruned", pruned).executeUpdate();
            if (failed != 1) {
                throw new WatermarkConflictException("DELIVERY_EVIDENCE_RETENTION_GAP_CONFLICT");
            }
            // A final attempt scan must clean up its candidate in this same transaction. The
            // source version stays unchanged; explicit recovery is the only path that advances it.
            return new DeliveryPollResult(false, true, cursor, cursor, null, null);
        }
        if (!"READY".equals(String.valueOf(watermark[2])) || watermark[3] != null
                || watermark[4] != null) {
            return DeliveryPollResult.deferred(cursor);
        }

        Object[] publication = (Object[]) em.createNativeQuery("""
                SELECT status,published_generation_id,attempt_generation_id
                FROM practice_revenue_publication
                WHERE publication_key='PRACTICE_CONTRIBUTION'
                """).getSingleResult();
        String status = String.valueOf(publication[0]);
        if (!"RUNNING".equals(status) || !attemptGenerationId.equals(value(publication[2]))) {
            throw new WatermarkConflictException("REVENUE_ATTEMPT_OWNER_LOST");
        }

        BigInteger target = currentRows.isEmpty() ? cursor : integer(currentRows.getLast());
        if (target.compareTo(cursor) <= 0) {
            return new DeliveryPollResult(false, false, cursor, cursor, null, null);
        }

        Object[] bounds = (Object[]) em.createNativeQuery("""
                SELECT MIN(d.dependent_recognized_month),MAX(d.dependent_recognized_month)
                FROM fact_change_log f
                JOIN practice_revenue_publication p
                  ON p.publication_key='PRACTICE_CONTRIBUTION'
                JOIN fact_practice_revenue_dependency_mat d
                  ON d.generation_id=p.published_generation_id
                  OR d.generation_id=p.attempt_generation_id
                WHERE f.id>:cursor AND f.id<=:target
                  AND f.change_type IN ('WORK','CONTRACT')
                  AND d.dependency_source_category='DELIVERY_EVIDENCE'
                  AND (
                    (f.change_type='WORK' AND d.source_work_uuid=f.source_id)
                    OR (f.change_type='CONTRACT'
                        AND d.source_contract_consultant_uuid=f.source_id)
                    OR (d.source_user_uuid=f.useruuid
                        AND f.affected_date>=d.delivery_start_date
                        AND f.affected_date<d.delivery_end_date)
                    OR (f.affected_date>=d.delivery_start_date
                        AND f.affected_date<d.delivery_end_date
                        AND (
                          (f.change_type='WORK' AND (
                            d.source_task_uuid IS NOT NULL
                            OR d.source_project_uuid IS NOT NULL
                            OR d.source_contract_project_uuid IS NOT NULL
                            OR d.source_contract_uuid IS NOT NULL))
                          OR
                          (f.change_type='CONTRACT' AND (
                            d.source_contract_uuid IS NOT NULL
                            OR d.source_capacity_user_uuid IS NOT NULL))
                        ))
                  )
                FOR UPDATE
                """).setParameter("cursor", cursor).setParameter("target", target).getSingleResult();
        LocalDate affectedStart = date(bounds[0]);
        LocalDate affectedEnd = date(bounds[1]);
        boolean relevant = affectedStart != null;
        int updated = em.createNativeQuery(DeliveryEvidencePoll.ADVANCE_SQL)
                .setParameter("target", target).setParameter("cursor", cursor)
                .setParameter("relevant", relevant ? 1 : 0)
                .setParameter("affectedStart", affectedStart)
                .setParameter("affectedEnd", affectedEnd).executeUpdate();
        if (updated != 1) throw new WatermarkConflictException("DELIVERY_EVIDENCE_CURSOR_CONFLICT");
        return new DeliveryPollResult(false, relevant, cursor, target, affectedStart, affectedEnd);
    }

    @Transactional
    public String beginImport(Source source) {
        String token = UUID.randomUUID().toString();
        int updated = em.createNativeQuery("""
                UPDATE practice_revenue_source_watermark
                SET source_state='RUNNING', attempt_token=:token, started_at=UTC_TIMESTAMP(6),
                    safe_reason='IMPORT_RUNNING', optimistic_version=optimistic_version+1
                WHERE source_name=:source AND source_state <> 'RUNNING'
                  AND recovery_token IS NULL
                  AND NOT (source_name='DELIVERY_EVIDENCE'
                           AND retention_gap_reason='FACT_CHANGE_LOG_RETENTION_GAP')
                """).setParameter("token", token).setParameter("source", source.name()).executeUpdate();
        if (updated != 1) throw new WatermarkConflictException("SOURCE_IMPORT_ALREADY_RUNNING");
        return token;
    }

    /**
     * Adds an asynchronous mutation to the durable source cohort without advancing its version.
     * Every committed event receives a monotonic sequence and its own token, including events
     * arriving while an earlier event is still running. The mutation itself must advance the
     * source version in the same transaction as its evidence write.
     */
    @Transactional
    public String beginAsyncMutation(Source source) {
        String token = UUID.randomUUID().toString();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT async_mutation_sequence, source_state
                FROM practice_revenue_source_watermark
                WHERE source_name=:source FOR UPDATE
                """).setParameter("source", source.name()).getResultList();
        if (rows.size() != 1) throw new WatermarkConflictException("SOURCE_ASYNC_MUTATION_UNKNOWN");
        BigInteger previousSequence = integer(rows.getFirst()[0]);
        BigInteger sequence = previousSequence.add(BigInteger.ONE);
        int updated = em.createNativeQuery("""
                UPDATE practice_revenue_source_watermark
                SET async_mutation_sequence=:sequence,
                    async_pending_count=async_pending_count+1,
                    source_state=CASE WHEN source_state='FAILED' THEN 'FAILED' ELSE 'RUNNING' END,
                    attempt_token=CASE WHEN source_state='FAILED' THEN NULL ELSE :token END,
                    started_at=CASE WHEN source_state='FAILED' THEN started_at ELSE UTC_TIMESTAMP(6) END,
                    safe_reason=CASE WHEN source_state='FAILED' THEN safe_reason
                                     ELSE 'ASYNC_MUTATION_RUNNING' END,
                    optimistic_version=optimistic_version+1
                WHERE source_name=:source AND async_mutation_sequence=:previousSequence
                """).setParameter("sequence", sequence).setParameter("token", token)
                .setParameter("source", source.name()).setParameter("previousSequence", previousSequence)
                .executeUpdate();
        if (updated != 1) throw new WatermarkConflictException("SOURCE_ASYNC_MUTATION_SEQUENCE_CONFLICT");
        int inserted = em.createNativeQuery("""
                INSERT INTO practice_revenue_async_mutation_attempt(
                    source_name, mutation_sequence, attempt_token, attempt_state)
                VALUES(:source,:sequence,:token,'RUNNING')
                """).setParameter("source", source.name()).setParameter("sequence", sequence)
                .setParameter("token", token).executeUpdate();
        if (inserted != 1) throw new WatermarkConflictException("SOURCE_ASYNC_MUTATION_ATTEMPT_NOT_RECORDED");
        return token;
    }

    @Transactional
    public void completeAsyncMutation(Source source, String token) {
        BigInteger sequence = lockRunningAsyncAttempt(source, token);
        int attemptUpdated = em.createNativeQuery("""
                UPDATE practice_revenue_async_mutation_attempt
                SET attempt_state='READY', completed_at=UTC_TIMESTAMP(6)
                WHERE source_name=:source AND mutation_sequence=:sequence
                  AND attempt_token=:token AND attempt_state='RUNNING'
                """).setParameter("source", source.name()).setParameter("sequence", sequence)
                .setParameter("token", token).executeUpdate();
        if (attemptUpdated != 1) throw new WatermarkConflictException("SOURCE_ASYNC_MUTATION_OWNER_LOST");

        int decremented = em.createNativeQuery("""
                UPDATE practice_revenue_source_watermark
                SET async_pending_count=async_pending_count-1,
                    async_completed_sequence=GREATEST(async_completed_sequence,:sequence),
                    last_observed_at=UTC_TIMESTAMP(6),
                    optimistic_version=optimistic_version+1
                WHERE source_name=:source AND async_pending_count>0
                """).setParameter("sequence", sequence).setParameter("source", source.name()).executeUpdate();
        if (decremented != 1) throw new WatermarkConflictException("SOURCE_ASYNC_MUTATION_PENDING_LOST");

        // READY is reachable only when every accepted attempt is complete and the monotonic
        // sequence has closed. An older completion can therefore never overwrite a later event.
        em.createNativeQuery("""
                UPDATE practice_revenue_source_watermark
                SET source_state='READY', attempt_token=NULL, completed_at=UTC_TIMESTAMP(6),
                    safe_reason='ASYNC_MUTATION_READY', optimistic_version=optimistic_version+1
                WHERE source_name=:source AND source_state='RUNNING'
                  AND async_pending_count=0
                  AND async_completed_sequence=async_mutation_sequence
                """).setParameter("source", source.name()).executeUpdate();

        // If counter/sequence closure is ever inconsistent, do not permit stale READY.
        em.createNativeQuery("""
                UPDATE practice_revenue_source_watermark
                SET source_state='FAILED', attempt_token=NULL, completed_at=UTC_TIMESTAMP(6),
                    safe_reason='ASYNC_MUTATION_SEQUENCE_GAP', optimistic_version=optimistic_version+1
                WHERE source_name=:source AND source_state='RUNNING'
                  AND async_pending_count=0
                  AND async_completed_sequence<>async_mutation_sequence
                """).setParameter("source", source.name()).executeUpdate();
    }

    @Transactional
    public void failAsyncMutation(Source source, String token) {
        BigInteger sequence = lockRunningAsyncAttempt(source, token);
        int attemptUpdated = em.createNativeQuery("""
                UPDATE practice_revenue_async_mutation_attempt
                SET attempt_state='FAILED', completed_at=UTC_TIMESTAMP(6)
                WHERE source_name=:source AND mutation_sequence=:sequence
                  AND attempt_token=:token AND attempt_state='RUNNING'
                """).setParameter("source", source.name()).setParameter("sequence", sequence)
                .setParameter("token", token).executeUpdate();
        if (attemptUpdated != 1) throw new WatermarkConflictException("SOURCE_ASYNC_MUTATION_OWNER_LOST");
        int updated = em.createNativeQuery("""
                UPDATE practice_revenue_source_watermark
                SET source_state='FAILED', attempt_token=NULL,
                    async_pending_count=async_pending_count-1,
                    completed_at=UTC_TIMESTAMP(6), last_observed_at=UTC_TIMESTAMP(6),
                    safe_reason='ASYNC_MUTATION_FAILED', optimistic_version=optimistic_version+1
                WHERE source_name=:source AND async_pending_count>0
                """).setParameter("source", source.name()).executeUpdate();
        if (updated != 1) throw new WatermarkConflictException("SOURCE_ASYNC_MUTATION_PENDING_LOST");
    }

    private BigInteger lockRunningAsyncAttempt(Source source, String token) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT mutation_sequence, attempt_state
                FROM practice_revenue_async_mutation_attempt
                WHERE source_name=:source AND attempt_token=:token FOR UPDATE
                """).setParameter("source", source.name()).setParameter("token", token).getResultList();
        if (rows.size() != 1 || !"RUNNING".equals(String.valueOf(rows.getFirst()[1]))) {
            throw new WatermarkConflictException("SOURCE_ASYNC_MUTATION_OWNER_LOST");
        }
        return integer(rows.getFirst()[0]);
    }

    @Transactional
    public void completeImport(Source source, String token, YearMonth affectedStart, YearMonth affectedEnd) {
        validateBounds(affectedStart, affectedEnd);
        int updated = em.createNativeQuery("""
                UPDATE practice_revenue_source_watermark
                SET source_state='READY', source_version=source_version+1, attempt_token=NULL,
                    completed_at=UTC_TIMESTAMP(6), changed_at=UTC_TIMESTAMP(6),
                    last_observed_at=UTC_TIMESTAMP(6), affected_start_month=:start,
                    affected_end_month=:end, safe_reason='IMPORT_READY',
                    optimistic_version=optimistic_version+1
                WHERE source_name=:source AND source_state='RUNNING' AND attempt_token=:token
                """).setParameter("start", affectedStart == null ? null : affectedStart.atDay(1))
                .setParameter("end", affectedEnd == null ? null : affectedEnd.atDay(1))
                .setParameter("source", source.name()).setParameter("token", token).executeUpdate();
        if (updated != 1) throw new WatermarkConflictException("SOURCE_IMPORT_OWNER_LOST");
        if (source.requiresCostFirst()) enqueueCostFirst(source, "DIRTY_MARKER");
    }

    @Transactional
    public void failImport(Source source, String token) {
        int updated = em.createNativeQuery("""
                UPDATE practice_revenue_source_watermark
                SET source_state='FAILED', attempt_token=NULL, completed_at=UTC_TIMESTAMP(6),
                    safe_reason='IMPORT_FAILED', optimistic_version=optimistic_version+1
                WHERE source_name=:source AND source_state='RUNNING' AND attempt_token=:token
                """).setParameter("source", source.name()).setParameter("token", token).executeUpdate();
        if (updated != 1) throw new WatermarkConflictException("SOURCE_IMPORT_OWNER_LOST");
    }

    /** Completes one stale-source recovery owner exactly once; handlers never publish revenue. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void completeRecovery(Source source,String token,YearMonth affectedStart,
                                 YearMonth affectedEnd,BigInteger observedFactChangeLogId){
        validateBounds(affectedStart,affectedEnd);
        boolean delivery=source==Source.DELIVERY_EVIDENCE;
        if(delivery!=(observedFactChangeLogId!=null)||observedFactChangeLogId!=null
                &&observedFactChangeLogId.signum()<0){
            throw new IllegalArgumentException("delivery recovery cursor contract violated");
        }
        String sql=delivery?"""
                UPDATE practice_revenue_source_watermark
                SET source_state='READY',source_version=source_version+1,attempt_token=NULL,
                    completed_at=UTC_TIMESTAMP(6),changed_at=UTC_TIMESTAMP(6),
                    last_observed_at=UTC_TIMESTAMP(6),affected_start_month=:start,
                    affected_end_month=:end,safe_reason='SOURCE_RECOVERY_READY',
                    last_fact_change_log_id=:cursor,
                    recovery_target_fact_change_log_id=NULL,recovery_token=NULL,
                    recovery_started_at=NULL,retention_gap_reason=NULL,
                    optimistic_version=optimistic_version+1
                WHERE source_name='DELIVERY_EVIDENCE' AND source_state='RUNNING'
                  AND attempt_token=:token AND recovery_token=:token
                  AND recovery_target_fact_change_log_id IS NOT NULL
                  AND :cursor>=recovery_target_fact_change_log_id
                  AND EXISTS (SELECT 1 FROM practice_contribution_publication_control c
                              WHERE c.control_id=1 AND c.refresh_enabled=FALSE
                                AND c.revenue_recovery_owner_token IS NULL)
                """:"""
                UPDATE practice_revenue_source_watermark
                SET source_state='READY',source_version=source_version+1,attempt_token=NULL,
                    completed_at=UTC_TIMESTAMP(6),changed_at=UTC_TIMESTAMP(6),
                    last_observed_at=UTC_TIMESTAMP(6),affected_start_month=:start,
                    affected_end_month=:end,safe_reason='SOURCE_RECOVERY_READY',
                    optimistic_version=optimistic_version+1
                WHERE source_name=:source AND source_state='RUNNING' AND attempt_token=:token
                """;
        var query=em.createNativeQuery(sql)
                .setParameter("start",affectedStart==null?null:affectedStart.atDay(1))
                .setParameter("end",affectedEnd==null?null:affectedEnd.atDay(1))
                .setParameter("token",token);
        if(delivery)query.setParameter("cursor",observedFactChangeLogId);
        else query.setParameter("source",source.name());
        if(query.executeUpdate()!=1)throw new WatermarkConflictException("SOURCE_RECOVERY_OWNER_LOST");
    }

    /** Fails only the still-owning recovery token; a later import/recovery can never be overwritten. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void failRecovery(Source source,String token){
        int updated=em.createNativeQuery("""
                UPDATE practice_revenue_source_watermark
                SET source_state='FAILED',attempt_token=NULL,completed_at=UTC_TIMESTAMP(6),
                    safe_reason=CASE WHEN source_name='DELIVERY_EVIDENCE'
                                     THEN 'FACT_CHANGE_LOG_RETENTION_GAP'
                                     ELSE 'SOURCE_RECOVERY_FAILED' END,
                    recovery_target_fact_change_log_id=CASE WHEN source_name='DELIVERY_EVIDENCE'
                                     THEN recovery_target_fact_change_log_id ELSE NULL END,
                    recovery_token=NULL,recovery_started_at=NULL,
                    optimistic_version=optimistic_version+1
                WHERE source_name=:source AND source_state='RUNNING' AND attempt_token=:token
                  AND (:source<>'DELIVERY_EVIDENCE' OR recovery_token=:token)
                """).setParameter("source",source.name()).setParameter("token",token).executeUpdate();
        if(updated!=1)throw new WatermarkConflictException("SOURCE_RECOVERY_OWNER_LOST");
    }

    public DirtyState state() {
        Object[] publication = (Object[]) em.createNativeQuery("""
                SELECT status, invoice_document_source_version, finance_gl_source_version,
                       currency_source_version, account_classification_source_version,
                       invoice_attribution_source_version, self_billed_source_version,
                       phantom_attribution_source_version, delivery_evidence_source_version,
                       practice_basis_input_source_version
                FROM practice_revenue_publication WHERE publication_key='PRACTICE_CONTRIBUTION'
                """).getSingleResult();
        @SuppressWarnings("unchecked")
        List<Object[]> liveRows = em.createNativeQuery("""
                SELECT source_name, source_version, source_state
                FROM practice_revenue_source_watermark ORDER BY source_name
                """).getResultList();
        Map<Source, BigInteger> published = new EnumMap<>(Source.class);
        for (int i = 0; i < Source.values().length; i++) published.put(Source.values()[i], integer(publication[i + 1]));
        Map<Source, BigInteger> dirty = new EnumMap<>(Source.class);
        for (Object[] row : liveRows) {
            Source source = Source.valueOf(String.valueOf(row[0]));
            BigInteger live = integer(row[1]);
            if (!"READY".equals(String.valueOf(row[2])) || live.compareTo(published.get(source)) > 0) {
                dirty.put(source, live);
            }
        }
        return new DirtyState(String.valueOf(publication[0]), Map.copyOf(dirty),
                dirty.keySet().stream().anyMatch(Source::requiresCostFirst));
    }

    @Transactional
    public BigInteger enqueueCostFirst(Source source, String triggerOrigin) {
        if (!source.requiresCostFirst()) throw new IllegalArgumentException("source is revenue-only");
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT b.full_refresh_version, b.incremental_refresh_version,
                       pb.source_version, gl.source_version, ac.source_version
                FROM bi_refresh_watermark b
                JOIN practice_revenue_source_watermark pb ON pb.source_name='PRACTICE_BASIS_INPUT'
                JOIN practice_revenue_source_watermark gl ON gl.source_name='FINANCE_GL'
                JOIN practice_revenue_source_watermark ac ON ac.source_name='ACCOUNT_CLASSIFICATION'
                WHERE b.pipeline_name='FACT_USER_DAY'
                """).getSingleResult();
        BigInteger full = integer(row[0]); BigInteger incremental = integer(row[1]);
        BigInteger basis = integer(row[2]); BigInteger finance = integer(row[3]); BigInteger classification = integer(row[4]);
        String cause = source == Source.PRACTICE_BASIS_INPUT ? "PRACTICE_BASIS_INPUT" : "COST_GL_INPUT";
        BigInteger causeVersion = source == Source.PRACTICE_BASIS_INPUT ? basis : finance.max(classification);
        String vector = hash(full, incremental, basis, finance, classification);
        String key = hash(cause, causeVersion, vector);
        em.createNativeQuery("""
                INSERT INTO practice_cost_basis_refresh_request(
                  request_key,cause,trigger_origin,cause_input_version,
                  expected_full_refresh_version,expected_incremental_refresh_version,
                  expected_practice_basis_input_version,expected_finance_gl_version,
                  expected_account_classification_version,input_vector_fingerprint)
                VALUES(:key,:cause,:origin,:causeVersion,:full,:incremental,:basis,:finance,:classification,:vector)
                ON DUPLICATE KEY UPDATE request_id=LAST_INSERT_ID(request_id)
                """).setParameter("key", key).setParameter("cause", cause)
                .setParameter("origin", triggerOrigin).setParameter("causeVersion", causeVersion)
                .setParameter("full", full).setParameter("incremental", incremental)
                .setParameter("basis", basis).setParameter("finance", finance)
                .setParameter("classification", classification).setParameter("vector", vector).executeUpdate();
        BigInteger requestId = integer(em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult());
        em.createNativeQuery("""
                UPDATE practice_operating_cost_publication
                SET latest_cost_basis_request_id=:id, latest_cost_basis_request_vector=:vector,
                    publication_version=publication_version+1 WHERE publication_id=1
                """).setParameter("id", requestId).setParameter("vector", vector).executeUpdate();
        // Defect 9: once this newer covering input is durably the latest, retire every dominated older
        // PENDING request to SUPERSEDED pointing at it. Idempotent duplicate enqueues reuse the existing
        // id and only supersede strictly-older rows, so a request can never supersede itself.
        em.createNativeQuery("CALL sp_supersede_dominated_cost_requests(:id)")
                .setParameter("id", requestId).executeUpdate();
        return requestId;
    }

    private static void validateBounds(YearMonth start, YearMonth end) {
        if ((start == null) != (end == null) || start != null && end.isBefore(start)) {
            throw new IllegalArgumentException("affected bounds must be both null or ordered");
        }
    }
    private static String hash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static BigInteger integer(Object value) { return value instanceof BigInteger i ? i : new BigInteger(value.toString()); }
    private static String value(Object value) { return value == null ? null : String.valueOf(value); }
    private static LocalDate date(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        return ((java.sql.Date) value).toLocalDate();
    }

    /** Ordering matches the frozen columns on practice_revenue_publication. */
    public enum Source {
        INVOICE_DOCUMENT(false), FINANCE_GL(true), CURRENCY(false), ACCOUNT_CLASSIFICATION(true),
        INVOICE_ATTRIBUTION(false), SELF_BILLED(false), PHANTOM_ATTRIBUTION(false),
        DELIVERY_EVIDENCE(false), PRACTICE_BASIS_INPUT(true);
        private final boolean costFirst;
        Source(boolean costFirst) { this.costFirst = costFirst; }
        public boolean requiresCostFirst() { return costFirst; }
    }
    public record DirtyState(String publicationStatus, Map<Source, BigInteger> dirtyVersions,
                             boolean costFirstRequired) {
        public boolean dirty() { return !dirtyVersions.isEmpty(); }
    }
    public record DeliveryPollResult(boolean deferred, boolean relevant, BigInteger previousCursor,
                                     BigInteger observedCursor, LocalDate affectedStart,
                                     LocalDate affectedEnd) {
        static DeliveryPollResult deferred(BigInteger cursor) {
            return new DeliveryPollResult(true, false, cursor, cursor, null, null);
        }
    }
    public static class WatermarkConflictException extends IllegalStateException {
        public WatermarkConflictException(String message) { super(message); }
    }
}
