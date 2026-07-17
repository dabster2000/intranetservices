package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.invoice.services.RegisteredDeliveryEvidenceResolver;
import dk.trustworks.intranet.aggregates.practices.model.PracticeRevenueAllocation;
import dk.trustworks.intranet.aggregates.practices.model.PracticeRevenueDependency;
import dk.trustworks.intranet.aggregates.practices.model.PracticeRevenueItem;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Versioned revenue builder with token ownership, immutable writes, and short final CAS. */
@JBossLog
@ApplicationScoped
public class PracticeRevenueMaterializationService {
    static final String PUBLICATION_KEY = "PRACTICE_CONTRIBUTION";
    static final String REVENUE_LOCK = "practice_revenue";
    static final String AUTO_ATTRIBUTION_ALGORITHM_VERSION = "PRACTICE_AUTO_ATTRIBUTION_V1";
    static final String AUTO_ATTRIBUTION_SOURCE_KIND = "REGISTERED_WORK_DISTRIBUTION";

    @Inject EntityManager em;
    @Inject PracticeRevenueValuationService valuationService;
    @Inject PracticeRevenueAllocationService allocationService;
    @Inject RegisteredDeliveryEvidenceResolver registeredDeliveryResolver;
    @Inject PracticeCostSnapshotProvider costSnapshotProvider;
    @Inject PracticeRevenueDirtyMarker dirtyMarker;
    @Inject MeterRegistry registry;

    @ConfigProperty(name="practices.contribution.named-lock-wait", defaultValue="PT30S") Duration lockWait;
    @ConfigProperty(name="practices.contribution.query-timeout", defaultValue="PT2M") Duration queryTimeout;
    @ConfigProperty(name="practices.contribution.build-transaction-timeout", defaultValue="PT1H")
    Duration buildTransactionTimeout;

    public Result refresh() {
        long started=System.nanoTime();
        Attempt attempt = QuarkusTransaction.requiringNew().call(this::startAttempt);
        if (attempt == null) {
            recordState("NOT_STARTED");
            recordDuration(started);
            return Result.notStarted();
        }
        try {
            // The build+persist is one atomic transaction whose real duration exceeds Narayana's
            // 600s default (a staging run was reaper-aborted at 600s and surfaced ~953s in).
            Result result=QuarkusTransaction.requiringNew()
                    .timeout(transactionTimeoutSeconds(buildTransactionTimeout))
                    .call(() -> buildAndPublish(attempt));
            recordState(result.status());
            return result;
        } catch (RevenueBasisCoverageMissException miss) {
            // Fail closed and escalate: durably advance the manifest input version and enqueue a cost-first
            // DEPENDENCY_MANIFEST_INPUT request. Both steps commit independently of the aborted attempt so
            // the endpoint stays unavailable only until cost rebuilds an expanded basis and revenue reruns.
            try {
                QuarkusTransaction.requiringNew().run(() -> escalateDependencyManifestMiss(miss));
                QuarkusTransaction.requiringNew().run(() -> failAndCleanup(attempt, "BASIS_COVERAGE_MISS"));
            } catch (RuntimeException cleanupFailure) { miss.addSuppressed(cleanupFailure); }
            recordState("BASIS_COVERAGE_MISS");
            throw miss;
        } catch (RuntimeException failure) {
            try { QuarkusTransaction.requiringNew().run(() -> failAndCleanup(attempt, safeFailure(failure))); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            recordState("FAILED");
            throw failure;
        } finally {
            recordDuration(started);
        }
    }

    /**
     * Proves every consumed dependency date is inside the certified basis coverage interval. A date outside
     * that interval means the basis was built before this evidence was introduced (a BASIS_COVERAGE_MISS):
     * the attempt fails closed rather than silently widening the basis or publishing an uncovered generation.
     */
    void assertConsumedDependenciesCovered(Attempt attempt, BuildCandidate candidate) {
        Object[] bounds = (Object[]) nativeQuery("""
                SELECT coverage_start_date, coverage_end_date, dependency_manifest_fingerprint
                FROM practice_basis_generation WHERE generation_id=:generation
                """).setParameter("generation", attempt.basisGenerationId()).getSingleResult();
        LocalDate coverageStart = localDate(bounds[0]);
        LocalDate coverageEnd = localDate(bounds[1]);
        // recognizedMonth and the *start* of a delivery/capacity interval are inclusive instants. The
        // delivery/capacity *end* fields are EXCLUSIVE bounds: PracticeBasisMaterializationService clamps
        // open capacity intervals to coverageEnd.plusDays(1) and delivery ends are deliveryDate.plusDays(1),
        // so an exclusive end up to and including coverageEnd+1 is fully covered. Comparing them inclusively
        // (isAfter(coverageEnd)) would flag every active consultant's clamped capacity envelope and every
        // last-coverage-day work row as a miss — a guaranteed BASIS_COVERAGE_MISS loop.
        LocalDate coverageEndExclusive = coverageEnd.plusDays(1);
        Bounds miss = new Bounds();
        for (DependencyEnvelope dependency : candidate.dependencies()) {
            for (LocalDate inclusive : new LocalDate[]{dependency.recognizedMonth(),
                    dependency.deliveryStartDate(), dependency.sourceCapacityStartDate()}) {
                if (inclusive == null) continue;
                if (inclusive.isBefore(coverageStart) || inclusive.isAfter(coverageEnd)) miss.add(inclusive);
            }
            for (LocalDate exclusiveEnd : new LocalDate[]{dependency.deliveryEndDate(),
                    dependency.sourceCapacityEndDate()}) {
                if (exclusiveEnd == null) continue;
                if (exclusiveEnd.isBefore(coverageStart) || exclusiveEnd.isAfter(coverageEndExclusive)) {
                    miss.add(exclusiveEnd);
                }
            }
        }
        if (miss.min != null) throw new RevenueBasisCoverageMissException(miss.min, miss.max);
    }

    private static final class Bounds {
        LocalDate min;
        LocalDate max;
        void add(LocalDate date) {
            min = (min == null || date.isBefore(min)) ? date : min;
            max = (max == null || date.isAfter(max)) ? date : max;
        }
    }

    /**
     * Escalates a revenue-detected coverage miss to a cost-first DEPENDENCY_MANIFEST_INPUT request. The
     * fingerprint is left null so the cost owner recomputes and certifies the authoritative expanded manifest.
     */
    void escalateDependencyManifestMiss(RevenueBasisCoverageMissException miss) {
        nativeQuery("CALL sp_advance_practice_dependency_manifest_input(:start, :end, :fingerprint)")
                .setParameter("start", miss.affectedStart())
                .setParameter("end", miss.affectedEnd())
                .setParameter("fingerprint", null)
                .executeUpdate();
    }

    private static LocalDate localDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    Attempt startAttempt() {
        Object[] row = (Object[]) nativeQuery("""
                SELECT c.refresh_enabled, c.control_version, c.revenue_recovery_owner_token,
                       r.status, r.owner_token, r.publication_version,
                       o.refresh_state, o.generation_at, o.practice_basis_generation_id,
                       o.certified_cost_basis_request_id, o.certified_cost_basis_request_vector,
                       b.full_refresh_version,
                       id.source_version, fg.source_version, cu.source_version, ac.source_version,
                       ia.source_version, sb.source_version, pa.source_version, de.source_version,
                       pb.source_version,
                       id.source_state, fg.source_state, cu.source_state, ac.source_state,
                       ia.source_state, sb.source_state, pa.source_state, de.source_state, pb.source_state
                FROM practice_contribution_publication_control c
                JOIN practice_revenue_publication r ON r.publication_key='PRACTICE_CONTRIBUTION'
                JOIN practice_operating_cost_publication o ON o.publication_id=1
                JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
                JOIN practice_revenue_source_watermark id ON id.source_name='INVOICE_DOCUMENT'
                JOIN practice_revenue_source_watermark fg ON fg.source_name='FINANCE_GL'
                JOIN practice_revenue_source_watermark cu ON cu.source_name='CURRENCY'
                JOIN practice_revenue_source_watermark ac ON ac.source_name='ACCOUNT_CLASSIFICATION'
                JOIN practice_revenue_source_watermark ia ON ia.source_name='INVOICE_ATTRIBUTION'
                JOIN practice_revenue_source_watermark sb ON sb.source_name='SELF_BILLED'
                JOIN practice_revenue_source_watermark pa ON pa.source_name='PHANTOM_ATTRIBUTION'
                JOIN practice_revenue_source_watermark de ON de.source_name='DELIVERY_EVIDENCE'
                JOIN practice_revenue_source_watermark pb ON pb.source_name='PRACTICE_BASIS_INPUT'
                WHERE c.control_id=1 FOR UPDATE
                """).getSingleResult();
        if (!bool(row[0]) || row[2] != null || "RUNNING".equals(text(row[3]))) return null;
        if ("READY".equals(text(row[3])) && !dirtyMarker.state().dirty()) return null;
        if (!"READY".equals(text(row[6])) || row[7] == null || row[8] == null || row[9] == null) {
            throw new PublicationConflictException("COST_BASIS_NOT_READY");
        }
        Object[] certificateRow = (Object[]) nativeQuery("""
                SELECT status, expected_full_refresh_version, expected_incremental_refresh_version,
                       expected_practice_basis_input_version, expected_finance_gl_version,
                       expected_account_classification_version, input_vector_fingerprint,
                       (SELECT incremental_refresh_version FROM bi_refresh_watermark
                        WHERE pipeline_name='FACT_USER_DAY')
                FROM practice_cost_basis_refresh_request
                WHERE request_id=:requestId
                """).setParameter("requestId", row[9]).getSingleResult();
        CostCertificate certificate = new CostCertificate(text(certificateRow[0]), integer(certificateRow[1]),
                integer(certificateRow[2]), integer(certificateRow[3]), integer(certificateRow[4]),
                integer(certificateRow[5]), text(certificateRow[6]));
        BigInteger incrementalRefreshVersion = integer(certificateRow[7]);
        if (!certificate.matches(integer(row[11]), incrementalRefreshVersion, integer(row[20]),
                integer(row[13]), integer(row[15]), text(row[10]))) {
            throw new PublicationConflictException("COST_BASIS_NOT_CERTIFIED");
        }
        for (int i=21; i<30; i++) if (!"READY".equals(text(row[i]))) {
            throw new PublicationConflictException("SOURCE_WATERMARK_NOT_READY");
        }
        String generation = UUID.randomUUID().toString(); String owner = UUID.randomUUID().toString();
        int updated = nativeQuery("""
                UPDATE practice_revenue_publication
                SET status='RUNNING', owner_token=:owner, attempt_generation_id=:generation,
                    lock_acquired_at=UTC_TIMESTAMP(6), started_at=UTC_TIMESTAMP(6), failed_at=NULL,
                    failure_code=NULL, paired_cost_generation_at=:costGeneration,
                    practice_basis_generation_id=:basis, full_bi_refresh_version=:full,
                    invoice_document_source_version=:id, finance_gl_source_version=:fg,
                    currency_source_version=:cu, account_classification_source_version=:ac,
                    invoice_attribution_source_version=:ia, self_billed_source_version=:sb,
                    phantom_attribution_source_version=:pa, delivery_evidence_source_version=:de,
                    practice_basis_input_source_version=:pb, shared_control_version=:control,
                    publication_version=publication_version+1
                WHERE publication_key='PRACTICE_CONTRIBUTION' AND publication_version=:version
                  AND status <> 'RUNNING' AND owner_token IS NULL
                """).setParameter("owner", owner).setParameter("generation", generation)
                .setParameter("costGeneration", row[7]).setParameter("basis", row[8])
                .setParameter("full", row[11]).setParameter("id", row[12]).setParameter("fg", row[13])
                .setParameter("cu", row[14]).setParameter("ac", row[15]).setParameter("ia", row[16])
                .setParameter("sb", row[17]).setParameter("pa", row[18]).setParameter("de", row[19])
                .setParameter("pb", row[20]).setParameter("control", row[1])
                .setParameter("version", row[5]).executeUpdate();
        if (updated != 1) throw new PublicationConflictException("REVENUE_ATTEMPT_CAS_FAILED");
        return new Attempt(generation, owner, integer(row[1]), toDateTime(row[7]), text(row[8]),
                integer(row[9]), text(row[10]), integer(row[11]), incrementalRefreshVersion, vector(row, 12));
    }

    Result buildAndPublish(Attempt attempt) {
        if (!acquireLock(REVENUE_LOCK)) {
            recordLock("timeout");
            throw new PublicationConflictException("REVENUE_LOCK_TIMEOUT");
        }
        recordLock("acquired");
        try {
            requireNotInterrupted();
            verifyAttempt(attempt);
            BuildCandidate candidate = buildCandidate(attempt);
            requireNotInterrupted();
            validate(candidate);
            // Design §10 point 5/6: prove the consumed dependency set is covered by the certified basis
            // before publishing. A miss fails closed here and escalates DEPENDENCY_MANIFEST_INPUT in refresh().
            assertConsumedDependenciesCovered(attempt, candidate);
            recordCandidateMetrics(candidate);
            persist(attempt, candidate);
            requireNotInterrupted();
            PracticeRevenueDirtyMarker.DeliveryPollResult deliveryScan=
                    dirtyMarker.finalDeliveryEvidenceScan(attempt.generationId());
            if(supersedeForDeliveryAdvance(attempt,deliveryScan)){
                return new Result(true,attempt.generationId(),"SUPERSEDED",0,0);
            }
            verifyAttempt(attempt);

            int published = nativeQuery("""
                    UPDATE practice_revenue_publication r
                    JOIN practice_contribution_publication_control c ON c.control_id=1
                    JOIN practice_operating_cost_publication o ON o.publication_id=1
                    JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
                    SET r.previous_generation_id=r.published_generation_id,
                        r.published_generation_id=r.attempt_generation_id,
                        r.attempt_generation_id=NULL, r.status='READY', r.owner_token=NULL,
                        r.source_snapshot_at=:snapshotAt,
                        r.source_snapshot_fact_change_log_high_water=:highWater,
                        r.coverage_start_month=:coverageStart, r.coverage_end_month=:coverageEnd,
                        r.booked_available=:bookedAvailable, r.booked_reason=:bookedReason,
                        r.booked_anchor_month=:bookedAnchor, r.booked_current_start_month=:bookedCurrentStart,
                        r.booked_current_end_month=:bookedCurrentEnd,
                        r.booked_prior_start_month=:bookedPriorStart, r.booked_prior_end_month=:bookedPriorEnd,
                        r.booked_plus_draft_available=:draftAvailable,
                        r.booked_plus_draft_reason=:draftReason, r.booked_plus_draft_anchor_month=:draftAnchor,
                        r.booked_plus_draft_current_start_month=:draftCurrentStart,
                        r.booked_plus_draft_current_end_month=:draftCurrentEnd,
                        r.booked_plus_draft_prior_start_month=:draftPriorStart,
                        r.booked_plus_draft_prior_end_month=:draftPriorEnd,
                        r.source_document_count=:documents, r.source_item_count=:items,
                        r.valued_item_count=:valued, r.allocation_count=:allocations,
                        r.missing_control_count=:missing, r.provisional_control_count=:provisional,
                        r.confirmed_attribution_count=:confirmed, r.estimated_attribution_count=:estimated,
                        r.unassigned_allocation_count=:unassigned, r.residual_control_count=:residual,
                        r.duplicate_risk_count=:duplicateRisk, r.item_control_total_dkk=:itemTotal,
                        r.allocation_total_dkk=:allocationTotal, r.gl_control_total_dkk=:glTotal,
                        r.reconciliation_gap_dkk=:gap, r.published_at=UTC_TIMESTAMP(6),
                        r.refreshed_at=UTC_TIMESTAMP(6), r.failure_code=NULL,
                        r.publication_version=r.publication_version+1
                    WHERE r.publication_key='PRACTICE_CONTRIBUTION'
                      AND r.status='RUNNING' AND r.owner_token=:owner
                      AND r.attempt_generation_id=:generation
                      AND r.shared_control_version=:controlVersion
                      AND r.paired_cost_generation_at=:costGeneration
                      AND r.practice_basis_generation_id=:basis
                      AND c.refresh_enabled=TRUE AND c.control_version=:controlVersion
                      AND c.revenue_recovery_owner_token IS NULL
                      AND o.refresh_state='READY' AND o.generation_at=:costGeneration
                      AND o.practice_basis_generation_id=:basis
                      AND o.certified_cost_basis_request_id=:costRequestId
                      AND o.certified_cost_basis_request_vector=:costRequestVector
                      AND b.full_refresh_version=:fullRefreshVersion
                      AND b.incremental_refresh_version=:incrementalRefreshVersion
                      AND NOT EXISTS (
                          SELECT 1 FROM practice_revenue_source_watermark w
                          WHERE w.source_state <> 'READY'
                             OR w.source_version <> CASE w.source_name
                                  WHEN 'INVOICE_DOCUMENT' THEN r.invoice_document_source_version
                                  WHEN 'FINANCE_GL' THEN r.finance_gl_source_version
                                  WHEN 'CURRENCY' THEN r.currency_source_version
                                  WHEN 'ACCOUNT_CLASSIFICATION' THEN r.account_classification_source_version
                                  WHEN 'INVOICE_ATTRIBUTION' THEN r.invoice_attribution_source_version
                                  WHEN 'SELF_BILLED' THEN r.self_billed_source_version
                                  WHEN 'PHANTOM_ATTRIBUTION' THEN r.phantom_attribution_source_version
                                  WHEN 'DELIVERY_EVIDENCE' THEN r.delivery_evidence_source_version
                                  WHEN 'PRACTICE_BASIS_INPUT' THEN r.practice_basis_input_source_version
                                  ELSE -1
                                END
                      )
                    """)
                    .setParameter("snapshotAt", candidate.snapshotAt())
                    .setParameter("highWater", deliveryScan.observedCursor())
                    .setParameter("coverageStart", candidate.coverageStart()).setParameter("coverageEnd", candidate.coverageEnd())
                    .setParameter("bookedAvailable", candidate.booked().available()).setParameter("bookedReason", candidate.booked().reason())
                    .setParameter("bookedAnchor", candidate.booked().anchor()).setParameter("bookedCurrentStart", candidate.booked().currentStart())
                    .setParameter("bookedCurrentEnd", candidate.booked().currentEnd()).setParameter("bookedPriorStart", candidate.booked().priorStart())
                    .setParameter("bookedPriorEnd", candidate.booked().priorEnd())
                    .setParameter("draftAvailable", candidate.bookedPlusDraft().available()).setParameter("draftReason", candidate.bookedPlusDraft().reason())
                    .setParameter("draftAnchor", candidate.bookedPlusDraft().anchor()).setParameter("draftCurrentStart", candidate.bookedPlusDraft().currentStart())
                    .setParameter("draftCurrentEnd", candidate.bookedPlusDraft().currentEnd()).setParameter("draftPriorStart", candidate.bookedPlusDraft().priorStart())
                    .setParameter("draftPriorEnd", candidate.bookedPlusDraft().priorEnd())
                    .setParameter("documents", candidate.documentCount()).setParameter("items", candidate.items().size())
                    .setParameter("valued", candidate.valuedItemCount()).setParameter("allocations", candidate.allocations().size())
                    .setParameter("missing", candidate.missingControlCount()).setParameter("provisional", candidate.provisionalControlCount())
                    .setParameter("confirmed", candidate.confirmedAttributionCount()).setParameter("estimated", candidate.estimatedAttributionCount())
                    .setParameter("unassigned", candidate.unassignedAllocationCount()).setParameter("residual", candidate.residualControlCount())
                    .setParameter("duplicateRisk", candidate.duplicateRiskCount()).setParameter("itemTotal", candidate.itemControlTotal())
                    .setParameter("allocationTotal", candidate.allocationTotal()).setParameter("glTotal", candidate.glControlTotal())
                    .setParameter("gap", candidate.reconciliationGap()).setParameter("owner", attempt.ownerToken())
                    .setParameter("generation", attempt.generationId()).setParameter("controlVersion", attempt.controlVersion())
                    .setParameter("costGeneration", attempt.costGenerationAt()).setParameter("basis", attempt.basisGenerationId())
                    .setParameter("costRequestId", attempt.costRequestId()).setParameter("costRequestVector", attempt.costRequestVector())
                    .setParameter("fullRefreshVersion", attempt.fullRefreshVersion())
                    .setParameter("incrementalRefreshVersion", attempt.incrementalRefreshVersion())
                    .executeUpdate();
            if (published != 1) throw new PublicationConflictException("REVENUE_PUBLICATION_CAS_FAILED");
            pruneOldGenerations();
            return new Result(true, attempt.generationId(), "READY", candidate.items().size(), candidate.allocations().size());
        } finally { releaseLock(REVENUE_LOCK); }
    }

    /** Keep the delivery cursor/version update in the successful transaction; throwing here
     * would roll the invalidation back and permit the stale candidate to be retried forever. */
    boolean supersedeForDeliveryAdvance(Attempt attempt,
                                        PracticeRevenueDirtyMarker.DeliveryPollResult scan){
        if(!scan.relevant())return false;
        failAndCleanup(attempt,"DELIVERY_EVIDENCE_ADVANCED");
        return true;
    }

    private void recordCandidateMetrics(BuildCandidate candidate){
        if(registry==null)return;
        registry.summary("practices.contribution.revenue.document.count").record(candidate.documentCount());
        registry.summary("practices.contribution.revenue.item.count").record(candidate.items().size());
        registry.summary("practices.contribution.revenue.allocation.count").record(candidate.allocations().size());
        registry.summary("practices.contribution.revenue.missing.control.count").record(candidate.missingControlCount());
        registry.summary("practices.contribution.revenue.provisional.control.count").record(candidate.provisionalControlCount());
        registry.summary("practices.contribution.revenue.unassigned.count").record(candidate.unassignedAllocationCount());
        registry.summary("practices.contribution.revenue.estimated.count").record(candidate.estimatedAttributionCount());
        registry.summary("practices.contribution.revenue.duplicate.risk.count").record(candidate.duplicateRiskCount());
        if(candidate.reconciliationGap()!=null)registry.summary(
                "practices.contribution.revenue.reconciliation.gap.dkk").record(
                candidate.reconciliationGap().abs().doubleValue());
    }

    private void recordDuration(long started){
        if(registry!=null)registry.timer("practices.contribution.revenue.refresh.duration")
                .record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private void recordState(String state){
        if(registry!=null)registry.counter("practices.contribution.revenue.refresh.state","state",state).increment();
    }

    private void recordLock(String outcome){
        if(registry!=null)registry.counter("practices.contribution.revenue.lock","outcome",outcome).increment();
    }

    /** Bounded default source adapter. Domain logic stays in valuation/allocation services. */
    BuildCandidate buildCandidate(Attempt attempt) {
        RevenueCoverage coverage = revenueCoverage(Instant.now());
        YearMonth first = coverage.first();
        YearMonth last = coverage.last();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery("""
                SELECT i.uuid, i.companyuuid, i.type, i.status, i.invoicedate, i.currency, i.discount,
                       ii.uuid, ii.origin, ii.hours, ii.rate, ii.consultantuuid,
                       ii.calculation_ref, ii.rule_id, ii.label,
                       ii.pricing_policy_version, ii.pricing_step_id, ii.pricing_step_sequence,
                       ii.pricing_rule_type, ii.pricing_input_fingerprint, ii.pricing_output_fingerprint,
                       ii.pricing_output_amount, ii.calculation_algorithm_version,
                       i.economics_booked_number,i.referencenumber,i.economics_voucher_number,
                       i.economics_entry_number,i.economics_accounting_year,i.debtor_companyuuid,
                       ii.source_item_uuid,ii.credit_copy_kind,ii.credit_copy_scope,ii.credit_copy_scale,
                       ii.credit_copy_original_source_native_amount,ii.credit_copy_fingerprint,
                       i.creditnote_for_uuid
                FROM invoices i LEFT JOIN invoiceitems ii ON ii.invoiceuuid=i.uuid
                WHERE i.status='CREATED' AND i.type IN ('INVOICE','PHANTOM','CREDIT_NOTE')
                  AND ((i.invoicedate >= :start AND i.invoicedate < :end)
                    OR i.uuid IN (
                        SELECT c.creditnote_for_uuid FROM invoices c
                        WHERE c.status='CREATED' AND c.type='CREDIT_NOTE'
                          AND c.invoicedate>=:start AND c.invoicedate<:end
                          AND c.creditnote_for_uuid IS NOT NULL
                    ))
                ORDER BY i.uuid, ii.position, ii.uuid
                """).setParameter("start", first.atDay(1)).setParameter("end", last.plusMonths(1).atDay(1))
                .getResultList();
        Map<String, MutableDocument> documents = new LinkedHashMap<>();
        Map<String, CreditEvidence> creditEvidence = new HashMap<>();
        for (Object[] row : rows) {
            MutableDocument doc = documents.computeIfAbsent(text(row[0]), ignored -> new MutableDocument(row));
            if (row[7] != null) {
                doc.items.add(new PracticeRevenueValuationService.ItemInput(
                    text(row[7]), itemOrigin(row[8]), text(row[9]), text(row[10]), text(row[11]), row[29] != null,
                    text(row[12]), text(row[13]), text(row[14]), row[12] != null,
                    text(row[15]), text(row[16]), row[17] == null ? null : number(row[17]).intValue(),
                    text(row[18]), text(row[19]), text(row[20]), decimalOrNull(row[21]), text(row[22])));
                creditEvidence.put(text(row[7]), new CreditEvidence(text(row[7]), text(row[29]), text(row[30]),
                        text(row[31]), decimalOrNull(row[32]), decimalOrNull(row[33]), text(row[34]),
                        text(row[0]), text(row[35]), deliveryOperand(row[9]), deliveryOperand(row[10]), text(row[8]),
                        text(row[11]),text(row[12]),text(row[13]), text(row[19]), text(row[20])));
            }
        }
        documents.values().forEach(document -> document.recognized = !document.date.isBefore(first.atDay(1))
                && document.date.isBefore(last.plusMonths(1).atDay(1)));
        LocalDate evidenceStart = documents.values().stream().map(document -> document.date).min(LocalDate::compareTo)
                .orElse(first.atDay(1));
        attachGlAndFxEvidence(documents, evidenceStart, last.atEndOfMonth());
        List<PracticeRevenueValuationService.DocumentInput> valuationInputs = documents.values().stream()
                .map(MutableDocument::toInput).toList();
        var valued = valuationService.value(valuationInputs);

        Map<String, List<PracticeRevenueAllocationService.SourceEvidence>> sources = loadAttributionSources(
                attempt.basisGenerationId(), evidenceStart, last.atEndOfMonth());
        Map<String, PracticeRevenueAllocationService.SourceEvidence> selfBilledAssignments =
                loadSelfBilledAssignments(attempt.basisGenerationId(), evidenceStart, last.atEndOfMonth());
        DeliveryEvidenceBundle prospectiveDelivery =
                loadProspectiveDeliverySources(attempt.basisGenerationId(), evidenceStart, last.atEndOfMonth());
        DeliveryEvidenceBundle legacyDirect =
                loadLegacyDirectSources(attempt.basisGenerationId(), evidenceStart, last.atEndOfMonth());
        DeliveryEvidenceBundle registeredDelivery =
                loadRegisteredDeliverySources(attempt.basisGenerationId(), evidenceStart, last.atEndOfMonth());
        List<ItemEnvelope> items = new ArrayList<>(); List<AllocationEnvelope> allocations = new ArrayList<>();
        List<DependencyEnvelope> dependencies = new ArrayList<>();
        Map<String,PracticeRevenueAllocationService.AllocationResult> sourceAllocationByItem=new HashMap<>();
        Map<String,List<PracticeRevenueAllocationService.AllocationResult>> sourceAllocationsByDocument=new HashMap<>();
        Map<String,PracticeRevenueValuationService.DocumentValuation> recognizedValuationByDocument=
                valued.documents().stream().collect(java.util.stream.Collectors.toMap(
                        PracticeRevenueValuationService.DocumentValuation::documentUuid,value->value));
        Map<String,PracticeRevenueValuationService.ItemControl> itemControlsByUuid=valued.documents().stream()
                .flatMap(document->document.items().stream()).filter(item->item.sourceItemUuid()!=null)
                .collect(java.util.stream.Collectors.toMap(
                        PracticeRevenueValuationService.ItemControl::sourceItemUuid,item->item,(left,right)->left));
        List<PracticeRevenueValuationService.DocumentValuation> orderedDocuments=valued.documents().stream()
                .sorted(Comparator.comparing(document -> document.documentType()==PracticeRevenueValuationService.DocumentType.CREDIT_NOTE))
                .toList();
        for (var document : orderedDocuments) {
            MutableDocument raw = documents.get(document.documentUuid());
            List<PracticeRevenueAllocationService.AllocationResult> baseAllocations = new ArrayList<>();
            List<PracticeRevenueValuationService.ItemControl> orderedItems = document.items().stream()
                    .sorted(Comparator.comparing(item -> item.itemCategory()
                            == PracticeRevenueValuationService.ItemCategory.COMMERCIAL_ADJUSTMENT))
                    .toList();
            for (var item : orderedItems) {
                List<PracticeRevenueAllocationService.SourceEvidence> itemSources = new ArrayList<>();
                if(document.documentType()==PracticeRevenueValuationService.DocumentType.CREDIT_NOTE){
                    CreditEvidence proof=creditEvidence.get(item.sourceItemUuid());
                    if(proof!=null&&"SOURCE_INVOICE".equals(proof.copyScope())){
                        MutableDocument sourceRaw=documents.get(proof.linkedSourceDocumentUuid());
                        PracticeRevenueValuationService.DocumentValuation sourceValuation=
                                recognizedValuationByDocument.get(proof.linkedSourceDocumentUuid());
                        if(sourceValuation==null&&sourceRaw!=null){
                            sourceValuation=valueDependencySource(sourceRaw.toInput(),valuationInputs);
                        }
                        itemSources.add(sourceInvoiceCreditEvidence(item,proof,sourceRaw,sourceValuation,
                                sourceAllocationsByDocument.getOrDefault(proof.linkedSourceDocumentUuid(),List.of())));
                    }else{
                        itemSources.add(creditEvidence(item,proof,sourceAllocationByItem,creditEvidence));
                        if(proof!=null&&proof.linkedSourceDocumentUuid()!=null){
                            itemSources.add(exactRuleCreditEvidence(item,proof,sourceAllocationByItem,
                                    creditEvidence,itemControlsByUuid));
                            PracticeRevenueValuationService.DocumentValuation sourceValuation=
                                    recognizedValuationByDocument.get(proof.linkedSourceDocumentUuid());
                            MutableDocument sourceRaw=documents.get(proof.linkedSourceDocumentUuid());
                            if(sourceValuation==null&&sourceRaw!=null){
                                sourceValuation=valueDependencySource(sourceRaw.toInput(),valuationInputs);
                            }
                            itemSources.add(completeSourceInvoiceDistribution(sourceValuation,
                                    sourceAllocationsByDocument.getOrDefault(
                                            proof.linkedSourceDocumentUuid(),List.of())));
                        }
                    }
                }else{
                    List<PracticeRevenueAllocationService.SourceEvidence> storedSources =
                            sources.getOrDefault(item.sourceItemUuid(), List.of());
                    if (item.itemCategory()
                            == PracticeRevenueValuationService.ItemCategory.COMMERCIAL_ADJUSTMENT) {
                        PracticeRevenueAllocationService.SourceEvidence persisted = storedSources.stream()
                                .filter(source -> source.tier()
                                        == PracticeRevenueAllocationService.SourceTier.PERSISTED)
                                .findFirst().orElse(null);
                        PracticeRevenueAllocationService.SourceEvidence adjustmentEvidence;
                        if (persisted != null) {
                            adjustmentEvidence = new PracticeRevenueAllocationService.SourceEvidence(
                                        PracticeRevenueAllocationService.SourceTier.BASE_DISTRIBUTION,
                                        persisted.state(), persisted.source(), persisted.attributionStatus(),
                                        persisted.residualPermitted(), persisted.candidates(), persisted.reason());
                        } else {
                            adjustmentEvidence = baseDistributionEvidence(baseAllocations);
                            PracticeRevenueAllocationService.SourceEvidence fallback =
                                    registeredDelivery.evidenceFor(document.documentUuid());
                            if (adjustmentEvidence.state()
                                    == PracticeRevenueAllocationService.EvidenceState.ABSENT
                                    && fallback != null) {
                                adjustmentEvidence = new PracticeRevenueAllocationService.SourceEvidence(
                                        PracticeRevenueAllocationService.SourceTier.BASE_DISTRIBUTION,
                                        fallback.state(), fallback.source(), fallback.attributionStatus(),
                                        fallback.residualPermitted(), fallback.candidates(), fallback.reason());
                            }
                        }
                        itemSources.add(adjustmentEvidence);
                    } else {
                        PracticeRevenueAllocationService.SourceEvidence selfBilled=
                                selfBilledAssignments.get(item.sourceItemUuid());
                        if(selfBilled!=null){
                            itemSources.add(selfBilled);
                            itemSources.addAll(storedSources.stream().filter(source->source.tier()
                                    !=PracticeRevenueAllocationService.SourceTier.HUMAN).toList());
                        }else{
                            itemSources.addAll(storedSources);
                        }
                        PracticeRevenueAllocationService.SourceEvidence delivery = prospectiveDelivery.evidenceFor(item.sourceItemUuid());
                        if (delivery != null) itemSources.add(delivery);
                        PracticeRevenueAllocationService.SourceEvidence direct = legacyDirect.evidenceFor(item.sourceItemUuid());
                        if (direct != null) {
                            if (direct.state()==PracticeRevenueAllocationService.EvidenceState.INVALID
                                    && direct.reason()==PracticeRevenueAllocationService.ReasonCode.DELIVERY_EVIDENCE_AMBIGUOUS) {
                                itemSources.removeIf(source -> source.source()
                                        == PracticeRevenueAllocationService.AttributionSource.PERSISTED_AUTO);
                            }
                            itemSources.add(direct);
                        }
                        PracticeRevenueAllocationService.SourceEvidence fallback =
                                registeredDelivery.evidenceFor(document.documentUuid());
                        if (fallback != null) itemSources.add(fallback);
                    }
                }
                var allocationRequest = new PracticeRevenueAllocationService.AllocationRequest(
                        item, document.documentType(), itemSources);
                PracticeRevenueAllocationService.EvidenceScope evidenceScope = item.itemControlDkk() == null
                        && (item.rowKind() == PracticeRevenueValuationService.ItemRowKind.SOURCE_ITEM
                        || item.rowKind() == PracticeRevenueValuationService.ItemRowKind.DOCUMENT_EVIDENCE)
                        ? allocationService.resolveEvidenceScope(allocationRequest) : null;
                var allocated = allocationService.allocate(allocationRequest);
                if (document.documentType() == PracticeRevenueValuationService.DocumentType.INVOICE
                        && item.itemCategory() == PracticeRevenueValuationService.ItemCategory.DELIVERY_BASE) {
                    baseAllocations.add(allocated);
                }
                if(item.sourceItemUuid()!=null) sourceAllocationByItem.put(item.sourceItemUuid(),allocated);
                if(document.documentType()!=PracticeRevenueValuationService.DocumentType.CREDIT_NOTE){
                    sourceAllocationsByDocument.computeIfAbsent(document.documentUuid(),ignored->new ArrayList<>())
                            .add(allocated);
                }
                if(raw.recognized){
                    items.add(new ItemEnvelope(raw.companyUuid, "CREATED", item, document, evidenceScope,
                            creditEvidence.get(item.sourceItemUuid())));
                    allocations.add(new AllocationEnvelope(item.itemControlKey(), allocated));
                    dependencies.add(DependencyEnvelope.document(item, attempt.basisGenerationId()));
                    if (document.documentType() != PracticeRevenueValuationService.DocumentType.CREDIT_NOTE) {
                        addDeliveryDependencies(dependencies, item,
                                prospectiveDelivery.dependenciesFor(item.sourceItemUuid()),
                                attempt.basisGenerationId());
                        addDeliveryDependencies(dependencies, item,
                                legacyDirect.dependenciesFor(item.sourceItemUuid()),
                                attempt.basisGenerationId());
                        addDeliveryDependencies(dependencies, item,
                                registeredDelivery.dependenciesFor(document.documentUuid()),
                                attempt.basisGenerationId());
                    }
                    CreditEvidence credit=creditEvidence.get(item.sourceItemUuid());
                    if(credit!=null&&credit.sourceItemUuid()!=null) {
                        dependencies.add(DependencyEnvelope.credit(item,credit.linkedSourceDocumentUuid(),credit.sourceItemUuid(),attempt.basisGenerationId()));
                        addDeliveryDependencies(dependencies, item,
                                prospectiveDelivery.dependenciesFor(credit.sourceItemUuid()),
                                attempt.basisGenerationId());
                        addDeliveryDependencies(dependencies, item,
                                legacyDirect.dependenciesFor(credit.sourceItemUuid()),
                                attempt.basisGenerationId());
                        addDeliveryDependencies(dependencies, item,
                                registeredDelivery.dependenciesFor(credit.linkedSourceDocumentUuid()),
                                attempt.basisGenerationId());
                    }
                    if(credit!=null&&credit.linkedSourceDocumentUuid()!=null){
                        MutableDocument sourceDocument=documents.get(credit.linkedSourceDocumentUuid());
                        if(sourceDocument!=null){
                            for(var sourceItem:sourceDocument.items){
                                addDeliveryDependencies(dependencies,item,
                                        prospectiveDelivery.dependenciesFor(sourceItem.itemUuid()),
                                        attempt.basisGenerationId());
                                addDeliveryDependencies(dependencies,item,
                                        legacyDirect.dependenciesFor(sourceItem.itemUuid()),
                                        attempt.basisGenerationId());
                            }
                        }
                        addDeliveryDependencies(dependencies,item,
                                registeredDelivery.dependenciesFor(credit.linkedSourceDocumentUuid()),
                                attempt.basisGenerationId());
                    }
                }
            }
        }
        Window booked = window(costSnapshotProvider.getSnapshot(CostSource.BOOKED).canonical());
        Window draft = window(costSnapshotProvider.getSnapshot(CostSource.BOOKED_PLUS_DRAFT).canonical());
        int recognizedDocuments=(int)documents.values().stream().filter(document->document.recognized).count();
        return summarize(recognizedDocuments, List.copyOf(items), allocations, dependencies,
                first.atDay(1), last.atDay(1), booked, draft);
    }

    static RevenueCoverage revenueCoverage(Instant now) {
        Objects.requireNonNull(now, "now");
        LocalDate reportingToday = now.atZone(UtilizationCalculationHelper.REPORTING_ZONE).toLocalDate();
        YearMonth last = YearMonth.from(reportingToday).minusMonths(1);
        return new RevenueCoverage(last.minusMonths(59), last);
    }

    static PracticeRevenueAllocationService.SourceEvidence baseDistributionEvidence(
            List<PracticeRevenueAllocationService.AllocationResult> baseItems) {
        if (baseItems == null || baseItems.isEmpty()) {
            return PracticeRevenueAllocationService.SourceEvidence.absent(
                    PracticeRevenueAllocationService.SourceTier.BASE_DISTRIBUTION);
        }
        Map<BaseRecipientKey, BaseRecipientWeight> weights = new LinkedHashMap<>();
        BigDecimal totalPositive = BigDecimal.ZERO;
        boolean estimated = false;
        for (PracticeRevenueAllocationService.AllocationResult result : baseItems) {
            if (result.allocations().isEmpty()
                    || result.allocations().stream().map(PracticeRevenueAllocationService.Allocation::allocationDkk)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add)
                    .compareTo(result.allocatedControlDkk()) != 0) {
                return PracticeRevenueAllocationService.SourceEvidence.invalid(
                        PracticeRevenueAllocationService.SourceTier.BASE_DISTRIBUTION,
                        PracticeRevenueAllocationService.AttributionSource.BASE_DISTRIBUTION,
                        PracticeRevenueAllocationService.ReasonCode.ATTRIBUTION_INVALID);
            }
            for (PracticeRevenueAllocationService.Allocation allocation : result.allocations()) {
                BaseRecipientKey key = new BaseRecipientKey(allocation.consultantUuid(), allocation.segmentId(),
                        allocation.effectivePracticeCode(), allocation.practiceResolutionMethod(),
                        allocation.historicalPracticeFallback(), allocation.deliveryStart(),
                        allocation.deliveryEndExclusive(), allocation.attributionSourceType(),
                        allocation.status());
                weights.computeIfAbsent(key, ignored -> new BaseRecipientWeight())
                        .add(allocation.unroundedAllocationDkk(),
                                result.itemControlKey() + ":" + allocation.sequence());
                estimated |= allocation.status() == PracticeRevenueAllocationService.AttributionStatus.ESTIMATED;
            }
        }
        for (BaseRecipientWeight weight : weights.values()) {
            if (weight.amount.signum() > 0) totalPositive = totalPositive.add(weight.amount);
        }
        if (totalPositive.signum() == 0) {
            return PracticeRevenueAllocationService.SourceEvidence.absent(
                    PracticeRevenueAllocationService.SourceTier.BASE_DISTRIBUTION);
        }
        List<PracticeRevenueAllocationService.RecipientCandidate> candidates = new ArrayList<>();
        boolean includesUnassigned = false;
        for (var entry : weights.entrySet()) {
            if (entry.getValue().amount.signum() <= 0) continue;
            BaseRecipientKey key = entry.getKey();
            BigDecimal share = entry.getValue().amount.divide(totalPositive, java.math.MathContext.DECIMAL128)
                    .setScale(18, java.math.RoundingMode.HALF_UP);
            if (key.segment() == PracticeRevenueAllocationService.SegmentId.UNASSIGNED
                    || key.consultantUuid() == null) {
                includesUnassigned = true;
                continue;
            }
            PracticeRevenueAllocationService.ConsultantType consultantType =
                    key.segment() == PracticeRevenueAllocationService.SegmentId.EXTERNAL
                            ? PracticeRevenueAllocationService.ConsultantType.EXTERNAL
                            : PracticeRevenueAllocationService.ConsultantType.INTERNAL;
            candidates.add(new PracticeRevenueAllocationService.RecipientCandidate(
                    "BASE:" + sha256(String.join("|", entry.getValue().sourceIds.stream().sorted().toList())),
                    key.consultantUuid(), key.practiceCode(), consultantType,
                    key.deliveryStart(), key.deliveryEndExclusive(), "BASE_DISTRIBUTION", share,
                    key.practiceResolutionMethod(), key.historicalPracticeFallback(), false,
                    key.segment(), String.join(",", entry.getValue().sourceIds.stream().sorted().toList()),
                    null, false));
        }
        if (candidates.isEmpty()) {
            return PracticeRevenueAllocationService.SourceEvidence.absent(
                    PracticeRevenueAllocationService.SourceTier.BASE_DISTRIBUTION);
        }
        return new PracticeRevenueAllocationService.SourceEvidence(
                PracticeRevenueAllocationService.SourceTier.BASE_DISTRIBUTION,
                PracticeRevenueAllocationService.EvidenceState.PRESENT,
                PracticeRevenueAllocationService.AttributionSource.BASE_DISTRIBUTION,
                estimated ? PracticeRevenueAllocationService.AttributionStatus.ESTIMATED
                        : PracticeRevenueAllocationService.AttributionStatus.CONFIRMED,
                includesUnassigned, candidates, PracticeRevenueAllocationService.ReasonCode.NONE);
    }

    static PracticeRevenueAllocationService.SourceEvidence creditEvidence(
            PracticeRevenueValuationService.ItemControl creditItem,CreditEvidence proof,
            Map<String,PracticeRevenueAllocationService.AllocationResult> sourceAllocations,
            Map<String,CreditEvidence> itemEvidence){
        if(proof==null)return PracticeRevenueAllocationService.SourceEvidence.absent(
                PracticeRevenueAllocationService.SourceTier.CREDIT_COPY);
        if(creditItem.itemControlDkk()==null||proof.copyKind()==null)
            return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_COPY);
        if("SOURCE_INVOICE".equals(proof.copyScope()))
            return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_INVOICE);
        if("NONE".equals(proof.copyKind())){
            if(proof.sourceItemUuid()==null)return PracticeRevenueAllocationService.SourceEvidence.absent(
                    PracticeRevenueAllocationService.SourceTier.CREDIT_COPY);
            return inheritedCreditEvidence(creditItem,proof,sourceAllocations,itemEvidence,
                    PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_ITEM,
                    PracticeRevenueAllocationService.AttributionSource.CREDIT_SOURCE_ITEM);
        }
        if(!"SOURCE_ITEM".equals(proof.copyScope())||proof.sourceItemUuid()==null)
            return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_COPY);
        PracticeRevenueAllocationService.AllocationResult inherited=sourceAllocations.get(proof.sourceItemUuid());
        CreditEvidence source=itemEvidence.get(proof.sourceItemUuid());
        if(inherited==null||source==null||source.itemRate()==null||source.itemHours()==null
                ||!Objects.equals(source.itemDocumentUuid(),proof.linkedSourceDocumentUuid())
                ||proof.originalSourceNativeAmount()==null||proof.copyFingerprint()==null)
            return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_COPY);
        BigDecimal sourceNative=source.itemRate().multiply(source.itemHours()).setScale(12);
        BigDecimal currentNative=proof.itemRate().multiply(proof.itemHours()).setScale(12);
        String fingerprint=creditFingerprint(proof.sourceItemUuid(),proof,currentNative);
        if(sourceNative.compareTo(proof.originalSourceNativeAmount())!=0||!fingerprint.equals(proof.copyFingerprint()))
            return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_COPY);
        boolean valid=switch(proof.copyKind()){
            case "BYTE_IDENTICAL" -> currentNative.compareTo(sourceNative)==0
                    && new BigDecimal("1.000000000000000000").equals(proof.copyScale())
                    && Objects.equals(source.itemOrigin(),proof.itemOrigin())
                    && Objects.equals(source.itemRuleId(),proof.itemRuleId())
                    && Objects.equals(source.pricingInputFingerprint(),proof.pricingInputFingerprint())
                    && Objects.equals(source.pricingOutputFingerprint(),proof.pricingOutputFingerprint());
            case "SCALED" -> sourceNative.signum()!=0 && proof.copyScale()!=null
                    && currentNative.divide(sourceNative,java.math.MathContext.DECIMAL128)
                    .setScale(18,java.math.RoundingMode.HALF_UP).compareTo(proof.copyScale())==0
                    && proof.pricingInputFingerprint()==null&&proof.pricingOutputFingerprint()==null;
            case "RESIDUAL" -> proof.copyScale()==null&&sourceNative.signum()==0
                    || sourceNative.signum()!=0&&proof.copyScale()!=null
                    && currentNative.divide(sourceNative,java.math.MathContext.DECIMAL128)
                    .setScale(18,java.math.RoundingMode.HALF_UP).compareTo(proof.copyScale())==0;
            default -> false;
        };
        if(!valid||inherited.allocations().stream().anyMatch(allocation->allocation.consultantUuid()==null
                ||allocation.segmentId()==PracticeRevenueAllocationService.SegmentId.UNASSIGNED))
            return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_COPY);
        return inheritedCreditEvidence(creditItem,proof,sourceAllocations,itemEvidence,
                PracticeRevenueAllocationService.SourceTier.CREDIT_COPY,
                PracticeRevenueAllocationService.AttributionSource.CREDIT_COPY);
    }

    private static PracticeRevenueAllocationService.SourceEvidence inheritedCreditEvidence(
            PracticeRevenueValuationService.ItemControl creditItem,CreditEvidence proof,
            Map<String,PracticeRevenueAllocationService.AllocationResult> sourceAllocations,
            Map<String,CreditEvidence> itemEvidence,PracticeRevenueAllocationService.SourceTier tier,
            PracticeRevenueAllocationService.AttributionSource attributionSource){
        PracticeRevenueAllocationService.AllocationResult inherited=sourceAllocations.get(proof.sourceItemUuid());
        CreditEvidence source=itemEvidence.get(proof.sourceItemUuid());
        if(inherited==null||source==null
                ||!Objects.equals(source.itemDocumentUuid(),proof.linkedSourceDocumentUuid())
                ||inherited.allocations().isEmpty()
                ||inherited.allocations().stream().anyMatch(allocation->allocation.consultantUuid()==null
                ||allocation.segmentId()==PracticeRevenueAllocationService.SegmentId.UNASSIGNED
                ||allocation.status()==PracticeRevenueAllocationService.AttributionStatus.UNASSIGNED)){
            return invalidCredit(tier);
        }
        List<PracticeRevenueAllocationService.RecipientCandidate> recipients=new ArrayList<>();
        for(var allocation:inherited.allocations()){
            var consultantType=allocation.segmentId()==PracticeRevenueAllocationService.SegmentId.EXTERNAL
                    ?PracticeRevenueAllocationService.ConsultantType.EXTERNAL
                    :PracticeRevenueAllocationService.ConsultantType.INTERNAL;
            recipients.add(new PracticeRevenueAllocationService.RecipientCandidate(
                    "CREDIT:"+creditItem.sourceItemUuid()+":"+allocation.sequence(),allocation.consultantUuid(),
                    allocation.effectivePracticeCode(),consultantType,allocation.deliveryStart(),allocation.deliveryEndExclusive(),
                    "CREDIT_COPY",allocation.effectiveFraction(),allocation.practiceResolutionMethod(),
                    allocation.historicalPracticeFallback(),true,allocation.segmentId(),proof.sourceItemUuid(),
                    proof.linkedSourceDocumentUuid(),false));
        }
        return new PracticeRevenueAllocationService.SourceEvidence(tier,
                PracticeRevenueAllocationService.EvidenceState.PRESENT,
                attributionSource,inherited.status(),false,recipients,
                PracticeRevenueAllocationService.ReasonCode.NONE);
    }

    static PracticeRevenueAllocationService.SourceEvidence exactRuleCreditEvidence(
            PracticeRevenueValuationService.ItemControl creditItem,CreditEvidence proof,
            Map<String,PracticeRevenueAllocationService.AllocationResult> sourceAllocations,
            Map<String,CreditEvidence> itemEvidence,
            Map<String,PracticeRevenueValuationService.ItemControl> itemControls){
        var tier=PracticeRevenueAllocationService.SourceTier.CREDIT_EXACT_RULE;
        if(proof==null||proof.linkedSourceDocumentUuid()==null){
            return PracticeRevenueAllocationService.SourceEvidence.absent(tier);
        }
        if((proof.calculationRef()==null||proof.calculationRef().isBlank())
                &&(proof.itemRuleId()==null||proof.itemRuleId().isBlank())){
            return PracticeRevenueAllocationService.SourceEvidence.absent(tier);
        }
        List<String> matches=itemEvidence.values().stream()
                .filter(candidate->Objects.equals(candidate.itemDocumentUuid(),
                        proof.linkedSourceDocumentUuid()))
                .filter(candidate->!Objects.equals(candidate.itemUuid(),proof.itemUuid()))
                .filter(candidate->Objects.equals(candidate.consultantUuid(),proof.consultantUuid()))
                .filter(candidate->Objects.equals(candidate.calculationRef(),proof.calculationRef()))
                .filter(candidate->Objects.equals(candidate.itemRuleId(),proof.itemRuleId()))
                .filter(candidate->{
                    var sourceItem=itemControls.get(candidate.itemUuid());
                    return sourceItem!=null
                            &&sourceItem.itemCategory()==creditItem.itemCategory()
                            &&sourceItem.adjustmentSubtype()==creditItem.adjustmentSubtype();
                })
                .map(CreditEvidence::itemUuid)
                .sorted()
                .toList();
        if(matches.isEmpty())return PracticeRevenueAllocationService.SourceEvidence.absent(tier);
        if(matches.size()!=1)return invalidCredit(tier);
        CreditEvidence correlated=creditWithSource(proof,matches.getFirst());
        return inheritedCreditEvidence(creditItem,correlated,sourceAllocations,itemEvidence,tier,
                PracticeRevenueAllocationService.AttributionSource.CREDIT_EXACT_RULE);
    }

    private static CreditEvidence creditWithSource(CreditEvidence proof,String sourceItemUuid){
        return new CreditEvidence(proof.itemUuid(),sourceItemUuid,proof.copyKind(),proof.copyScope(),
                proof.copyScale(),proof.originalSourceNativeAmount(),proof.copyFingerprint(),
                proof.itemDocumentUuid(),proof.linkedSourceDocumentUuid(),proof.itemHours(),proof.itemRate(),
                proof.itemOrigin(),proof.consultantUuid(),proof.calculationRef(),proof.itemRuleId(),
                proof.pricingInputFingerprint(),proof.pricingOutputFingerprint());
    }

    PracticeRevenueValuationService.DocumentValuation valueDependencySource(
            PracticeRevenueValuationService.DocumentInput source,
            Collection<PracticeRevenueValuationService.DocumentInput> population){
        // Value this one bounded ONE-HOP source within the exact recognized-plus-dependency control
        // population so the generation-wide inverse Booked voucher-key uniqueness the main path enforces
        // also gates every dependency source, regardless of how many REVENUE rows its voucher has. Every
        // other document participates as dependency-only, so none of them is re-recognized as revenue.
        List<PracticeRevenueValuationService.DocumentInput> inputs=new ArrayList<>();
        inputs.add(source);
        for(PracticeRevenueValuationService.DocumentInput document:population){
            if(Objects.equals(document.documentUuid(),source.documentUuid()))continue;
            inputs.add(document.asDependencyOnly());
        }
        return valuationService.value(inputs).documents().stream()
                .filter(value->Objects.equals(value.documentUuid(),source.documentUuid()))
                .findFirst().orElse(null);
    }

    static PracticeRevenueAllocationService.SourceEvidence sourceInvoiceCreditEvidence(
            PracticeRevenueValuationService.ItemControl creditItem,CreditEvidence proof,
            MutableDocument sourceRaw,PracticeRevenueValuationService.DocumentValuation sourceValuation,
            List<PracticeRevenueAllocationService.AllocationResult> sourceAllocations){
        if(proof==null||sourceRaw==null||sourceValuation==null||creditItem.itemControlDkk()==null
                ||!"RESIDUAL".equals(proof.copyKind())||!"SOURCE_INVOICE".equals(proof.copyScope())
                ||proof.sourceItemUuid()!=null||proof.linkedSourceDocumentUuid()==null
                ||!Objects.equals(sourceRaw.uuid,proof.linkedSourceDocumentUuid())
                ||proof.copyScale()!=null||proof.originalSourceNativeAmount()==null
                ||proof.copyFingerprint()==null||proof.itemRate()==null||proof.itemHours()==null){
            return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_INVOICE);
        }
        BigDecimal sourceNative=sourceRaw.normalizedNativeControl();
        BigDecimal creditNative=proof.itemRate().multiply(proof.itemHours()).setScale(12);
        if(sourceNative==null||sourceNative.compareTo(proof.originalSourceNativeAmount())!=0
                ||!creditFingerprint(proof.linkedSourceDocumentUuid(),proof,creditNative)
                .equals(proof.copyFingerprint())){
            return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_INVOICE);
        }
        return completeSourceInvoiceDistribution(sourceValuation,sourceAllocations);
    }

    static PracticeRevenueAllocationService.SourceEvidence completeSourceInvoiceDistribution(
            PracticeRevenueValuationService.DocumentValuation source,
            List<PracticeRevenueAllocationService.AllocationResult> sourceAllocations){
        if(source==null||source.documentType()!=PracticeRevenueValuationService.DocumentType.INVOICE
                ||source.authoritativeControlDkk()==null||source.authoritativeControlDkk().signum()<=0
                ||source.items().isEmpty()||source.items().stream().anyMatch(item->item.syntheticResidual()
                ||item.itemControlDkk()==null)||sourceAllocations==null
                ||sourceAllocations.size()!=source.items().size()){
            return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_INVOICE);
        }
        Map<BaseRecipientKey,BaseRecipientWeight> weights=new LinkedHashMap<>();
        BigDecimal allocatedTotal=BigDecimal.ZERO.setScale(2);
        boolean estimated=false;
        for(var result:sourceAllocations){
            BigDecimal itemTotal=result.allocations().stream()
                    .map(PracticeRevenueAllocationService.Allocation::allocationDkk)
                    .reduce(BigDecimal.ZERO.setScale(2),BigDecimal::add);
            if(itemTotal.compareTo(result.allocatedControlDkk())!=0||result.allocations().isEmpty()
                    ||result.allocations().stream().anyMatch(allocation->allocation.consultantUuid()==null
                    ||allocation.segmentId()==PracticeRevenueAllocationService.SegmentId.UNASSIGNED
                    ||allocation.status()==PracticeRevenueAllocationService.AttributionStatus.UNASSIGNED)){
                return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_INVOICE);
            }
            allocatedTotal=allocatedTotal.add(itemTotal);
            for(var allocation:result.allocations()){
                BaseRecipientKey key=new BaseRecipientKey(allocation.consultantUuid(),allocation.segmentId(),
                        allocation.effectivePracticeCode(),allocation.practiceResolutionMethod(),
                        allocation.historicalPracticeFallback(),allocation.deliveryStart(),
                        allocation.deliveryEndExclusive(),allocation.attributionSourceType(),allocation.status());
                weights.computeIfAbsent(key,ignored->new BaseRecipientWeight()).add(allocation.allocationDkk(),
                        result.itemControlKey()+":"+allocation.sequence());
                estimated|=allocation.status()==PracticeRevenueAllocationService.AttributionStatus.ESTIMATED;
            }
        }
        if(allocatedTotal.compareTo(source.authoritativeControlDkk())!=0
                ||weights.values().stream().anyMatch(weight->weight.amount.signum()<0)){
            return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_INVOICE);
        }
        List<PracticeRevenueAllocationService.RecipientCandidate> recipients=new ArrayList<>();
        for(var entry:weights.entrySet()){
            if(entry.getValue().amount.signum()==0)continue;
            BaseRecipientKey key=entry.getKey();
            BigDecimal share=entry.getValue().amount.divide(source.authoritativeControlDkk(),
                    java.math.MathContext.DECIMAL128).setScale(18,java.math.RoundingMode.HALF_UP);
            recipients.add(new PracticeRevenueAllocationService.RecipientCandidate(
                    "SOURCE_INVOICE:"+sha256(String.join("|",entry.getValue().sourceIds.stream().sorted().toList())),
                    key.consultantUuid(),key.practiceCode(),key.segment()==PracticeRevenueAllocationService.SegmentId.EXTERNAL
                    ?PracticeRevenueAllocationService.ConsultantType.EXTERNAL
                    :PracticeRevenueAllocationService.ConsultantType.INTERNAL,key.deliveryStart(),key.deliveryEndExclusive(),
                    "CREDIT_SOURCE_INVOICE",share,key.practiceResolutionMethod(),key.historicalPracticeFallback(),
                    true,key.segment(),String.join(",",entry.getValue().sourceIds.stream().sorted().toList()),
                    source.documentUuid(),false));
        }
        if(recipients.isEmpty())return invalidCredit(PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_INVOICE);
        return new PracticeRevenueAllocationService.SourceEvidence(
                PracticeRevenueAllocationService.SourceTier.CREDIT_SOURCE_INVOICE,
                PracticeRevenueAllocationService.EvidenceState.PRESENT,
                PracticeRevenueAllocationService.AttributionSource.CREDIT_SOURCE_INVOICE,
                estimated?PracticeRevenueAllocationService.AttributionStatus.ESTIMATED
                        :PracticeRevenueAllocationService.AttributionStatus.CONFIRMED,
                false,recipients,PracticeRevenueAllocationService.ReasonCode.NONE);
    }

    private static PracticeRevenueAllocationService.SourceEvidence invalidCredit(
            PracticeRevenueAllocationService.SourceTier tier){
        PracticeRevenueAllocationService.AttributionSource source=switch(tier){
            case CREDIT_SOURCE_ITEM -> PracticeRevenueAllocationService.AttributionSource.CREDIT_SOURCE_ITEM;
            case CREDIT_SOURCE_INVOICE -> PracticeRevenueAllocationService.AttributionSource.CREDIT_SOURCE_INVOICE;
            default -> PracticeRevenueAllocationService.AttributionSource.CREDIT_COPY;
        };
        return PracticeRevenueAllocationService.SourceEvidence.invalid(tier,
                source,
                PracticeRevenueAllocationService.ReasonCode.ATTRIBUTION_INVALID);
    }

    static String creditFingerprint(String sourceKey,CreditEvidence item,BigDecimal nativeAmount){
        return sha256(String.join("|",sourceKey==null?"":sourceKey,item.copyKind()==null?"NONE":item.copyKind(),
                item.copyScope()==null?"":item.copyScope(),item.sourceItemUuid()==null?"":item.sourceItemUuid(),
                nativeAmount.toPlainString(),item.itemOrigin()==null?"":item.itemOrigin(),
                item.itemRuleId()==null?"":item.itemRuleId(),
                item.pricingInputFingerprint()==null?"":item.pricingInputFingerprint(),
                item.pricingOutputFingerprint()==null?"":item.pricingOutputFingerprint()));
    }

    private void attachGlAndFxEvidence(Map<String, MutableDocument> documents, LocalDate start, LocalDate end) {
        LocalDate fiscalStart = LocalDate.of(start.getMonthValue() >= 7 ? start.getYear() : start.getYear()-1, 7, 1);
        LocalDate fiscalEndExclusive = LocalDate.of(end.getMonthValue() >= 7 ? end.getYear()+1 : end.getYear(), 7, 1);
        @SuppressWarnings("unchecked")
        List<Object[]> glRows = nativeQuery("""
                SELECT fd.companyuuid,
                       CASE WHEN MONTH(fd.expensedate)>=7 THEN YEAR(fd.expensedate) ELSE YEAR(fd.expensedate)-1 END,
                       fd.vouchernumber,fd.journalnumber,fd.amount,
                       CONCAT(fd.entrynumber,':',fd.accountnumber),fd.invoicenumber,fd.entrynumber
                FROM finance_details fd
                JOIN accounting_accounts aa
                  ON aa.companyuuid=fd.companyuuid AND aa.account_code=fd.accountnumber
                 AND aa.cost_type='REVENUE'
                WHERE fd.postingstatus='BOOKED' AND fd.expensedate>=:start AND fd.expensedate<:end
                  AND fd.vouchernumber>0
                ORDER BY fd.companyuuid,2,fd.vouchernumber,fd.entrynumber,fd.accountnumber
                """).setParameter("start", fiscalStart).setParameter("end", fiscalEndExclusive).getResultList();
        List<StoredGl> gl = glRows.stream().map(row -> new StoredGl(text(row[0]), number(row[1]).intValue(),
                number(row[2]).longValue(), number(row[3]).longValue(), text(row[4]), text(row[5]),
                number(row[6]).longValue(), number(row[7]).longValue())).toList();
        Map<String,List<StoredGl>> glByGroup = gl.stream().collect(java.util.stream.Collectors.groupingBy(
                StoredGl::groupKey, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        for (MutableDocument document : documents.values()) {
            GlSelection selection = selectGl(document, gl, glByGroup);
            document.glEvidenceConflict = selection.conflict();
            document.glEntries.addAll(selection.rows().stream().map(row ->
                    new PracticeRevenueValuationService.GlEntry(row.groupKey(), row.companyUuid(),
                            row.financialYearStart(), "BOOKED", row.voucherNumber(), row.journalNumber(),
                            "REVENUE", row.amountText(), row.accountingIdentifier())).toList());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> fxRows = nativeQuery("""
                SELECT currency,month,conversion FROM currences
                WHERE month>=:startMonth AND month<=:endMonth
                ORDER BY currency,month,uuid
                """).setParameter("startMonth", YearMonth.from(start).toString().replace("-", ""))
                .setParameter("endMonth", YearMonth.from(end).toString().replace("-", ""))
                .getResultList();
        Map<String,List<String>> fx = new HashMap<>();
        for (Object[] row : fxRows) fx.computeIfAbsent(text(row[0]).toUpperCase()+":"+text(row[1]),
                ignored -> new ArrayList<>()).add(text(row[2]));
        for (MutableDocument document : documents.values()) {
            if (document.currency != null && !"DKK".equalsIgnoreCase(document.currency)) {
                String month = YearMonth.from(document.date).toString().replace("-", "");
                document.fxRates.addAll(fx.getOrDefault(document.currency.toUpperCase()+":"+month, List.of()));
            }
        }
    }

    private static GlSelection selectGl(MutableDocument document, List<StoredGl> all,
                                        Map<String,List<StoredGl>> byGroup) {
        int fiscal = document.date.getMonthValue()>=7?document.date.getYear():document.date.getYear()-1;
        PracticeRevenueGlVoucherResolver.Resolution resolution;
        if (document.type == PracticeRevenueValuationService.DocumentType.PHANTOM) {
            // != 0 (not > 0): a present-but-unparseable stored year is -1 and must conflict, not match.
            if (document.economicsAccountingYear != 0 && document.economicsAccountingYear != fiscal) {
                return new GlSelection(true, List.of());
            }
            if (document.economicsEntryNumber <= 0) {
                return new GlSelection(false, List.of());
            }
            resolution = PracticeRevenueGlVoucherResolver.resolve(
                    PracticeRevenueGlVoucherResolver.DocumentKind.PHANTOM, document.companyUuid, fiscal,
                    List.of(new PracticeRevenueGlVoucherResolver.Identifier(
                            PracticeRevenueGlVoucherResolver.IdentifierKind.ECONOMICS_ENTRY_NUMBER,
                            document.economicsEntryNumber)), all);
        } else {
            List<PracticeRevenueGlVoucherResolver.Identifier> identifiers = new ArrayList<>();
            if (document.economicsBookedNumber > 0)
                identifiers.add(new PracticeRevenueGlVoucherResolver.Identifier(
                        PracticeRevenueGlVoucherResolver.IdentifierKind.ECONOMICS_BOOKED_NUMBER,
                        document.economicsBookedNumber));
            if (document.referenceNumber > 0)
                identifiers.add(new PracticeRevenueGlVoucherResolver.Identifier(
                        PracticeRevenueGlVoucherResolver.IdentifierKind.REFERENCE_NUMBER,
                        document.referenceNumber));
            if (document.economicsVoucherNumber > 0)
                identifiers.add(new PracticeRevenueGlVoucherResolver.Identifier(
                        PracticeRevenueGlVoucherResolver.IdentifierKind.ECONOMICS_VOUCHER_NUMBER,
                        document.economicsVoucherNumber));
            resolution = PracticeRevenueGlVoucherResolver.resolve(
                    PracticeRevenueGlVoucherResolver.DocumentKind.ORDINARY, document.companyUuid, fiscal,
                    identifiers, all);
        }
        return switch (resolution.outcome()) {
            case MISSING -> new GlSelection(false, List.of());
            case AMBIGUOUS -> new GlSelection(true, List.of());
            case USABLE -> new GlSelection(false,
                    PracticeRevenueGlVoucherResolver.voucherGroup(resolution.voucherGroupKey(), byGroup));
        };
    }

    private Map<String, List<PracticeRevenueAllocationService.SourceEvidence>> loadAttributionSources(
            String basisGeneration, LocalDate start, LocalDate end) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery("""
                SELECT a.uuid, a.invoiceitem_uuid, a.consultant_uuid, a.share_pct, a.source,
                       b.practice_code, b.consultant_type, b.attribution_basis, i.type,
                       a.attribution_algorithm_version, a.attribution_source_kind,
                       a.attribution_dependency_fingerprint
                FROM invoice_item_attributions a
                JOIN invoiceitems ii ON ii.uuid=a.invoiceitem_uuid
                JOIN invoices i ON i.uuid=ii.invoiceuuid
                LEFT JOIN practice_user_effective_basis_mat b
                  ON b.generation_id=:basis AND b.user_uuid=a.consultant_uuid
                 AND i.invoicedate >= b.effective_from_date
                 AND i.invoicedate < b.effective_to_date_exclusive
                WHERE i.status='CREATED' AND i.invoicedate BETWEEN :start AND :end
                ORDER BY a.invoiceitem_uuid, a.uuid
                """).setParameter("basis", basisGeneration).setParameter("start", start).setParameter("end", end)
                .getResultList();
        Map<String,List<StoredAttribution>> candidates = new LinkedHashMap<>();
        for (Object[] row : rows) {
            candidates.computeIfAbsent(text(row[1]), ignored -> new ArrayList<>()).add(
                    new StoredAttribution(text(row[0]), text(row[2]), decimalOrNull(row[3]), text(row[4]),
                            text(row[5]), text(row[6]), text(row[7]), text(row[8]),
                            text(row[9]), text(row[10]), text(row[11])));
        }
        Map<String,List<PracticeRevenueAllocationService.SourceEvidence>> result = new HashMap<>();
        candidates.forEach((item, values) -> result.put(item, List.of(evidenceForStoredAttributions(values))));
        return result;
    }

    private Map<String,PracticeRevenueAllocationService.SourceEvidence> loadSelfBilledAssignments(
            String basisGeneration,LocalDate start,LocalDate end){
        @SuppressWarnings("unchecked")
        List<Object[]> rows=nativeQuery("""
                SELECT ii.uuid,a.uuid,a.consultant_uuid,a.work_year,a.work_month,a.share_amount,a.source,
                       b.practice_code,b.consultant_type,b.attribution_basis,
                       CAST(ii.hours*ii.rate AS DECIMAL(48,12)),
                       (SELECT CAST(SUM(s.amount) AS DECIMAL(48,12))
                          FROM selfbilled_line s
                         WHERE s.account_number=anchor.account_number
                           AND s.voucher_number=anchor.voucher_number)
                FROM selfbilled_assignment a
                JOIN selfbilled_line anchor ON anchor.uuid=a.selfbilled_line_uuid
                JOIN selfbilled_line line
                  ON line.account_number=anchor.account_number
                 AND line.voucher_number=anchor.voucher_number
                JOIN invoices i
                  ON i.type='PHANTOM' AND i.status='CREATED'
                 AND i.economics_entry_number=line.entry_number
                 AND i.companyuuid=line.debtor_company_uuid
                JOIN invoiceitems ii ON ii.invoiceuuid=i.uuid
                LEFT JOIN practice_user_effective_basis_mat b
                  ON b.generation_id=:basis AND b.user_uuid=a.consultant_uuid
                 AND STR_TO_DATE(CONCAT(a.work_year,'-',LPAD(a.work_month,2,'0'),'-01'),'%Y-%m-%d')
                     >=b.effective_from_date
                 AND STR_TO_DATE(CONCAT(a.work_year,'-',LPAD(a.work_month,2,'0'),'-01'),'%Y-%m-%d')
                     <b.effective_to_date_exclusive
                WHERE i.invoicedate BETWEEN :start AND :end
                ORDER BY ii.uuid,a.uuid
                """).setParameter("basis",basisGeneration).setParameter("start",start)
                .setParameter("end",end).getResultList();
        Map<String,List<StoredSelfBilledAssignment>> grouped=new LinkedHashMap<>();
        for(Object[] row:rows){
            StoredSelfBilledAssignment assignment=new StoredSelfBilledAssignment(text(row[0]),text(row[1]),
                    text(row[2]),number(row[3]).intValue(),number(row[4]).intValue(),decimalOrNull(row[5]),
                    text(row[6]),text(row[7]),text(row[8]),text(row[9]),decimalOrNull(row[10]),
                    decimalOrNull(row[11]));
            grouped.computeIfAbsent(assignment.itemUuid(),ignored->new ArrayList<>()).add(assignment);
        }
        Map<String,PracticeRevenueAllocationService.SourceEvidence> result=new HashMap<>();
        grouped.forEach((item,assignments)->result.put(item,selfBilledEvidence(assignments)));
        return result;
    }

    static PracticeRevenueAllocationService.SourceEvidence selfBilledEvidence(
            List<StoredSelfBilledAssignment> stored){
        if(stored==null||stored.isEmpty())return PracticeRevenueAllocationService.SourceEvidence.absent(
                PracticeRevenueAllocationService.SourceTier.HUMAN);
        StoredSelfBilledAssignment first=stored.getFirst();
        if(first.itemNativeAmount()==null||first.voucherNativeAmount()==null
                ||first.voucherNativeAmount().signum()==0
                ||first.itemNativeAmount().abs().subtract(first.voucherNativeAmount().abs()).abs()
                .compareTo(new BigDecimal("0.01"))>0
                ||stored.stream().anyMatch(row->!Objects.equals(first.itemUuid(),row.itemUuid())
                ||row.shareAmount()==null||(row.shareAmount().signum()!=0
                &&row.shareAmount().signum()!=first.voucherNativeAmount().signum()))){
            return PracticeRevenueAllocationService.SourceEvidence.invalid(
                    PracticeRevenueAllocationService.SourceTier.HUMAN,
                    PracticeRevenueAllocationService.AttributionSource.HUMAN,
                    PracticeRevenueAllocationService.ReasonCode.ATTRIBUTION_INVALID);
        }
        List<PracticeRevenueAllocationService.RecipientCandidate> recipients=new ArrayList<>();
        boolean estimated=false;
        for(StoredSelfBilledAssignment row:stored){
            if(row.shareAmount().signum()==0)continue;
            PracticeRevenueAllocationService.RecipientIdentity identity=recipientIdentity(
                    row.consultantUuid(),row.practiceCode(),row.consultantType(),row.attributionBasis());
            if(identity==null)return PracticeRevenueAllocationService.SourceEvidence.invalid(
                    PracticeRevenueAllocationService.SourceTier.HUMAN,
                    PracticeRevenueAllocationService.AttributionSource.HUMAN,
                    PracticeRevenueAllocationService.ReasonCode.MISSING_RECIPIENT);
            YearMonth month=YearMonth.of(row.workYear(),row.workMonth());
            BigDecimal fraction=row.shareAmount().abs().divide(first.voucherNativeAmount().abs(),
                    java.math.MathContext.DECIMAL128).setScale(18,java.math.RoundingMode.HALF_UP);
            recipients.add(new PracticeRevenueAllocationService.RecipientCandidate(row.assignmentUuid(),
                    row.consultantUuid(),row.practiceCode(),identity.consultantType(),month.atDay(1),
                    month.plusMonths(1).atDay(1),"SELFBILLED_ASSIGNMENT",fraction,
                    identity.practiceResolutionMethod(),identity.historicalPracticeFallback(),false,null,
                    row.assignmentUuid(),null,false));
            estimated|=!"HUMAN".equals(row.source());
        }
        if(recipients.isEmpty())return PracticeRevenueAllocationService.SourceEvidence.absent(
                PracticeRevenueAllocationService.SourceTier.HUMAN);
        return new PracticeRevenueAllocationService.SourceEvidence(
                PracticeRevenueAllocationService.SourceTier.HUMAN,
                PracticeRevenueAllocationService.EvidenceState.PRESENT,
                PracticeRevenueAllocationService.AttributionSource.HUMAN,
                estimated?PracticeRevenueAllocationService.AttributionStatus.ESTIMATED
                        :PracticeRevenueAllocationService.AttributionStatus.CONFIRMED,
                true,recipients,PracticeRevenueAllocationService.ReasonCode.NONE);
    }

    private DeliveryEvidenceBundle loadLegacyDirectSources(
            String basisGeneration, LocalDate start, LocalDate end) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery("""
                SELECT ii.uuid,ii.consultantuuid,b.practice_code,b.consultant_type,b.attribution_basis,
                       EXISTS(
                           SELECT 1 FROM work w
                           JOIN task t ON t.uuid=w.taskuuid
                           JOIN project p ON p.uuid=t.projectuuid
                           JOIN contract_project cp ON cp.projectuuid=p.uuid
                           WHERE p.uuid=i.projectuuid AND cp.contractuuid=i.contractuuid
                             AND w.registered>=STR_TO_DATE(CONCAT(i.year,'-',LPAD(i.month,2,'0'),'-01'),'%Y-%m-%d')
                             AND w.registered<DATE_ADD(STR_TO_DATE(CONCAT(i.year,'-',LPAD(i.month,2,'0'),'-01'),'%Y-%m-%d'),INTERVAL 1 MONTH)
                             AND NULLIF(TRIM(w.workas),'') IS NOT NULL AND w.workas<>w.useruuid
                       ) AS work_as_conflict,
                       i.projectuuid,i.contractuuid,
                       STR_TO_DATE(CONCAT(i.year,'-',LPAD(i.month,2,'0'),'-01'),'%Y-%m-%d'),
                       DATE_ADD(STR_TO_DATE(CONCAT(i.year,'-',LPAD(i.month,2,'0'),'-01'),'%Y-%m-%d'),INTERVAL 1 MONTH),
                       b.effective_from_date,b.effective_to_date_exclusive
                FROM invoiceitems ii
                JOIN invoices i ON i.uuid=ii.invoiceuuid
                LEFT JOIN practice_user_effective_basis_mat b
                  ON b.generation_id=:basis AND b.user_uuid=ii.consultantuuid
                 AND i.invoicedate>=b.effective_from_date AND i.invoicedate<b.effective_to_date_exclusive
                WHERE i.status='CREATED' AND i.invoicedate BETWEEN :start AND :end
                  AND i.type='INVOICE' AND ii.consultantuuid IS NOT NULL
                ORDER BY ii.uuid
                """).setParameter("basis", basisGeneration).setParameter("start", start).setParameter("end", end)
                .getResultList();
        Map<String, PracticeRevenueAllocationService.SourceEvidence> result = new HashMap<>();
        Map<String,List<DeliveryDependencySeed>> dependencies = new HashMap<>();
        for (Object[] row : rows) {
            String item = text(row[0]);
            dependencies.put(item,List.of(new DeliveryDependencySeed(null,text(row[1]),text(row[1]),
                    toDate(row[8]),toDate(row[9]),null,text(row[6]),null,text(row[7]),null,
                    row[10]==null?toDate(row[8]):toDate(row[10]),
                    row[11]==null?toDate(row[9]):toDate(row[11]))));
            if (bool(row[5])) {
                result.put(item, PracticeRevenueAllocationService.SourceEvidence.invalid(
                        PracticeRevenueAllocationService.SourceTier.LEGACY_DIRECT,
                        PracticeRevenueAllocationService.AttributionSource.LEGACY_DIRECT_FALLBACK,
                        PracticeRevenueAllocationService.ReasonCode.DELIVERY_EVIDENCE_AMBIGUOUS));
                continue;
            }
            PracticeRevenueAllocationService.ConsultantType consultantType = "EXTERNAL".equals(text(row[3]))
                    ? PracticeRevenueAllocationService.ConsultantType.EXTERNAL
                    : row[3] == null ? null : PracticeRevenueAllocationService.ConsultantType.INTERNAL;
            var candidate = new PracticeRevenueAllocationService.RecipientCandidate("DIRECT:"+item,
                    text(row[1]),text(row[2]),consultantType,null,null,"LEGACY_DIRECT_FALLBACK",
                    BigDecimal.ONE,PracticeRevenueAllocationService.PracticeResolutionMethod.MONTH_END_PRACTICE,
                    "CURRENT_PRACTICE_FALLBACK".equals(text(row[4])),false,null,null,null,false);
            result.put(item,new PracticeRevenueAllocationService.SourceEvidence(
                    PracticeRevenueAllocationService.SourceTier.LEGACY_DIRECT,
                    PracticeRevenueAllocationService.EvidenceState.PRESENT,
                    PracticeRevenueAllocationService.AttributionSource.LEGACY_DIRECT_FALLBACK,
                    PracticeRevenueAllocationService.AttributionStatus.ESTIMATED,false,List.of(candidate),
                    PracticeRevenueAllocationService.ReasonCode.NONE));
        }
        return new DeliveryEvidenceBundle(result,dependencies);
    }

    private DeliveryEvidenceBundle loadRegisteredDeliverySources(
            String basisGeneration, LocalDate start, LocalDate end) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery("""
                SELECT i.uuid,w.uuid,w.useruuid,COALESCE(NULLIF(TRIM(w.workas),''),w.useruuid),
                       w.registered,w.taskuuid,p.uuid,c.uuid,cp.uuid,cc.uuid,
                       CAST(w.workduration AS DECIMAL(24,6)),CAST(cc.rate AS DECIMAL(24,6)),
                       b.practice_code,b.consultant_type,b.attribution_basis,
                       b.effective_from_date,b.effective_to_date_exclusive,i.year,i.month
                FROM invoices i
                JOIN project p ON p.uuid=i.projectuuid
                JOIN contract_project cp ON cp.projectuuid=p.uuid AND cp.contractuuid=i.contractuuid
                JOIN contracts c ON c.uuid=cp.contractuuid
                LEFT JOIN task t ON t.projectuuid=p.uuid
                LEFT JOIN work w ON w.taskuuid=t.uuid
                 AND w.registered>=STR_TO_DATE(CONCAT(i.year,'-',LPAD(i.month,2,'0'),'-01'),'%Y-%m-%d')
                 AND w.registered<DATE_ADD(STR_TO_DATE(CONCAT(i.year,'-',LPAD(i.month,2,'0'),'-01'),'%Y-%m-%d'),INTERVAL 1 MONTH)
                LEFT JOIN contract_consultants cc
                  ON cc.contractuuid=c.uuid
                 AND cc.useruuid=COALESCE(NULLIF(TRIM(w.workas),''),w.useruuid)
                 AND cc.activefrom<=w.registered AND cc.activeto>=w.registered
                LEFT JOIN practice_user_effective_basis_mat b
                  ON b.generation_id=:basis
                 AND b.user_uuid=COALESCE(NULLIF(TRIM(w.workas),''),w.useruuid)
                 AND w.registered>=b.effective_from_date
                 AND w.registered<b.effective_to_date_exclusive
                WHERE i.status='CREATED' AND i.type='INVOICE'
                  AND i.invoicedate BETWEEN :start AND :end
                  AND i.contractuuid IS NOT NULL AND i.projectuuid IS NOT NULL
                ORDER BY i.uuid,w.uuid,cp.uuid,cc.uuid,b.effective_from_date
                """).setParameter("basis",basisGeneration).setParameter("start",start)
                .setParameter("end",end).getResultList();
        Map<String,List<RegisteredDeliveryEvidenceResolver.RawDeliveryRow>> byDocument=new LinkedHashMap<>();
        Map<String,Set<PracticeRevenueAllocationService.RecipientIdentity>> identities=new HashMap<>();
        Map<String,List<DeliveryDependencySeed>> dependencies=new HashMap<>();
        for(Object[] row:rows){
            String document=text(row[0]);
            if(row[1]!=null){
                byDocument.computeIfAbsent(document,ignored->new ArrayList<>()).add(
                        new RegisteredDeliveryEvidenceResolver.RawDeliveryRow(text(row[1]),text(row[2]),
                                text(row[3]),toDate(row[4]),text(row[5]),text(row[6]),text(row[7]),
                                text(row[8]),text(row[9]),text(row[10]),text(row[11])));
                PracticeRevenueAllocationService.RecipientIdentity identity=recipientIdentity(
                        text(row[3]),text(row[12]),text(row[13]),text(row[14]));
                identities.computeIfAbsent(document+":"+text(row[1]),ignored->new java.util.LinkedHashSet<>())
                        .add(identity);
            }
            LocalDate deliveryDate=row[4]==null
                    ?LocalDate.of(number(row[17]).intValue(),number(row[18]).intValue(),1)
                    :toDate(row[4]);
            LocalDate deliveryEnd=row[4]==null?deliveryDate.plusMonths(1):deliveryDate.plusDays(1);
            LocalDate capacityStart=row[15]==null?deliveryDate:toDate(row[15]);
            LocalDate capacityEnd=row[16]==null?deliveryEnd:toDate(row[16]);
            dependencies.computeIfAbsent(document,ignored->new ArrayList<>()).add(
                    new DeliveryDependencySeed(text(row[1]),text(row[2]),text(row[3]),
                            deliveryDate,deliveryEnd,text(row[5]),text(row[6]),
                            text(row[8]),text(row[7]),text(row[9]),capacityStart,capacityEnd));
        }
        Map<String,PracticeRevenueAllocationService.SourceEvidence> result=new HashMap<>();
        byDocument.forEach((document,raw)->{
            List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery> resolved=
                    registeredDeliveryResolver.resolveRows(raw);
            result.put(document,allocationService.registeredDeliveryEvidence(resolved,delivery->{
                Set<PracticeRevenueAllocationService.RecipientIdentity> candidates=
                        identities.getOrDefault(document+":"+delivery.workUuid(),Set.of());
                return candidates.size()==1?candidates.iterator().next():null;
            }));
        });
        return new DeliveryEvidenceBundle(result,immutableDependencyMap(dependencies));
    }

    private static PracticeRevenueAllocationService.RecipientIdentity recipientIdentity(
            String consultantUuid,String practiceCode,String consultantType,String attributionBasis){
        if(consultantUuid==null||consultantType==null||attributionBasis==null)return null;
        PracticeRevenueAllocationService.ConsultantType type="EXTERNAL".equals(consultantType)
                ?PracticeRevenueAllocationService.ConsultantType.EXTERNAL
                :"INTERNAL".equals(consultantType)
                ?PracticeRevenueAllocationService.ConsultantType.INTERNAL:null;
        if(type==null)return null;
        return new PracticeRevenueAllocationService.RecipientIdentity(consultantUuid,practiceCode,type,
                "CURRENT_PRACTICE_FALLBACK".equals(attributionBasis)
                        ?PracticeRevenueAllocationService.PracticeResolutionMethod.MONTH_END_PRACTICE
                        :PracticeRevenueAllocationService.PracticeResolutionMethod.DATED_DELIVERY,
                "CURRENT_PRACTICE_FALLBACK".equals(attributionBasis));
    }

    private DeliveryEvidenceBundle loadProspectiveDeliverySources(
            String basisGeneration, LocalDate start, LocalDate end) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = nativeQuery("""
                SELECT d.invoice_item_uuid,d.work_uuid,d.registrant_uuid,d.effective_consultant_uuid,
                       d.delivery_date,d.task_uuid,d.project_uuid,d.contract_uuid,
                       d.contract_project_uuid,d.contract_consultant_uuid,
                       d.normalized_duration,d.normalized_rate,d.delivery_value,
                       d.rate_resolution_status,d.contribution_algorithm_version,
                       d.item_fingerprint,d.distribution_fingerprint,
                       ii.hours,ii.rate,ii.origin,ii.rule_id,
                       b.practice_code,b.consultant_type,b.attribution_basis,
                       b.effective_from_date,b.effective_to_date_exclusive
                FROM practice_invoice_item_delivery_source d
                JOIN invoiceitems ii ON ii.uuid=d.invoice_item_uuid
                JOIN invoices i ON i.uuid=ii.invoiceuuid
                LEFT JOIN practice_user_effective_basis_mat b
                  ON b.generation_id=:basis AND b.user_uuid=d.effective_consultant_uuid
                 AND d.delivery_date >= b.effective_from_date
                 AND d.delivery_date < b.effective_to_date_exclusive
                WHERE i.status='CREATED' AND i.invoicedate BETWEEN :start AND :end
                ORDER BY d.invoice_item_uuid,d.work_uuid
                """).setParameter("basis", basisGeneration).setParameter("start", start).setParameter("end", end)
                .getResultList();
        Map<String,List<StoredDelivery>> grouped = new LinkedHashMap<>();
        Map<String,List<DeliveryDependencySeed>> dependencies=new HashMap<>();
        for (Object[] row : rows) {
            StoredDelivery value = new StoredDelivery(text(row[0]), text(row[1]), text(row[2]), text(row[3]),
                    toDate(row[4]), text(row[5]), text(row[6]), text(row[7]), text(row[8]), text(row[9]),
                    decimalOrNull(row[10]), decimalOrNull(row[11]), decimalOrNull(row[12]), text(row[13]),
                    text(row[14]), text(row[15]), text(row[16]), deliveryOperand(row[17]),
                    deliveryOperand(row[18]), text(row[19]), text(row[20]), text(row[21]), text(row[22]),
                    text(row[23]));
            grouped.computeIfAbsent(value.invoiceItemUuid(), ignored -> new ArrayList<>()).add(value);
            LocalDate capacityStart=row[24]==null?value.deliveryDate():toDate(row[24]);
            LocalDate capacityEnd=row[25]==null?value.deliveryDate().plusDays(1):toDate(row[25]);
            dependencies.computeIfAbsent(value.invoiceItemUuid(),ignored->new ArrayList<>()).add(
                    new DeliveryDependencySeed(value.workUuid(),value.registrantUuid(),
                            value.effectiveConsultantUuid(),value.deliveryDate(),
                            value.deliveryDate().plusDays(1),value.taskUuid(),value.projectUuid(),
                            value.contractProjectUuid(),value.contractUuid(),value.contractConsultantUuid(),
                            capacityStart,capacityEnd));
        }
        Map<String, PracticeRevenueAllocationService.SourceEvidence> result = new HashMap<>();
        grouped.forEach((item, values) -> result.put(item,
                prospectiveEvidenceForStoredRows(values, allocationService)));
        return new DeliveryEvidenceBundle(result,immutableDependencyMap(dependencies));
    }

    /** Future generated lineage must remain byte-identical to its server-owned item and row proof. */
    static PracticeRevenueAllocationService.SourceEvidence prospectiveEvidenceForStoredRows(
            List<StoredDelivery> stored, PracticeRevenueAllocationService allocator) {
        if (stored == null || stored.isEmpty()) return null;
        try {
            StoredDelivery first = stored.getFirst();
            if (stored.stream().anyMatch(row -> !Objects.equals(first.invoiceItemUuid(), row.invoiceItemUuid())
                    || !"PRACTICE_DELIVERY_LINEAGE_V1".equals(row.algorithmVersion()))) {
                return invalidProspective();
            }
            String distribution = deliveryDistributionFingerprint(stored);
            String item = deliveryItemFingerprint(first, distribution);
            if (stored.stream().anyMatch(row -> !distribution.equals(row.distributionFingerprint())
                    || !item.equals(row.itemFingerprint()))) return invalidProspective();

            Map<String, StoredDelivery> byWork = new LinkedHashMap<>();
            List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery> deliveries = new ArrayList<>();
            for (StoredDelivery row : stored.stream().sorted(Comparator.comparing(StoredDelivery::workUuid)).toList()) {
                if (byWork.putIfAbsent(row.workUuid(), row) != null || row.duration() == null
                        || row.deliveryDate() == null || row.effectiveConsultantUuid() == null) {
                    return invalidProspective();
                }
                RegisteredDeliveryEvidenceResolver.RateResolutionStatus state =
                        RegisteredDeliveryEvidenceResolver.RateResolutionStatus.valueOf(row.rateStatus());
                deliveries.add(new RegisteredDeliveryEvidenceResolver.ResolvedDelivery(row.workUuid(),
                        row.registrantUuid(), row.effectiveConsultantUuid(), row.deliveryDate(), row.taskUuid(),
                        row.projectUuid(), row.contractUuid(), row.contractProjectUuid(),
                        row.contractConsultantUuid(), row.duration(), row.rate(), row.deliveryValue(), state));
            }
            PracticeRevenueAllocationService.SourceEvidence resolved = allocator.registeredDeliveryEvidence(
                    deliveries, delivery -> {
                        StoredDelivery row = byWork.get(delivery.workUuid());
                        PracticeRevenueAllocationService.ConsultantType consultantType =
                                "EXTERNAL".equals(row.consultantType())
                                        ? PracticeRevenueAllocationService.ConsultantType.EXTERNAL
                                        : row.consultantType() == null ? null
                                        : PracticeRevenueAllocationService.ConsultantType.INTERNAL;
                        return new PracticeRevenueAllocationService.RecipientIdentity(
                                delivery.effectiveConsultantUuid(), row.practiceCode(), consultantType,
                                PracticeRevenueAllocationService.PracticeResolutionMethod.DATED_DELIVERY,
                                "CURRENT_PRACTICE_FALLBACK".equals(row.attributionBasis()));
                    });
            if (resolved.state() != PracticeRevenueAllocationService.EvidenceState.PRESENT) {
                return invalidProspective();
            }
            return new PracticeRevenueAllocationService.SourceEvidence(
                    PracticeRevenueAllocationService.SourceTier.PROSPECTIVE_DELIVERY_LINEAGE,
                    PracticeRevenueAllocationService.EvidenceState.PRESENT,
                    PracticeRevenueAllocationService.AttributionSource.PROSPECTIVE_DELIVERY_LINEAGE,
                    PracticeRevenueAllocationService.AttributionStatus.ESTIMATED, true,
                    resolved.candidates(), PracticeRevenueAllocationService.ReasonCode.NONE);
        } catch (RuntimeException malformed) {
            return invalidProspective();
        }
    }

    private static PracticeRevenueAllocationService.SourceEvidence invalidProspective() {
        return PracticeRevenueAllocationService.SourceEvidence.invalid(
                PracticeRevenueAllocationService.SourceTier.PROSPECTIVE_DELIVERY_LINEAGE,
                PracticeRevenueAllocationService.AttributionSource.PROSPECTIVE_DELIVERY_LINEAGE,
                PracticeRevenueAllocationService.ReasonCode.DELIVERY_EVIDENCE_AMBIGUOUS);
    }

    static String deliveryDistributionFingerprint(List<StoredDelivery> rows) {
        return sha256(rows.stream().sorted(Comparator.comparing(StoredDelivery::workUuid))
                .map(PracticeRevenueMaterializationService::deliveryRowFingerprint)
                .reduce((left,right)->left+"|"+right).orElse(""));
    }

    private static String deliveryRowFingerprint(StoredDelivery row) {
        if (row.duration()==null || row.rate()==null) throw new IllegalArgumentException("incomplete lineage");
        return sha256(String.join("|", row.invoiceItemUuid(), row.workUuid(), row.registrantUuid(),
                row.effectiveConsultantUuid(), String.valueOf(row.deliveryDate()), String.valueOf(row.taskUuid()),
                String.valueOf(row.projectUuid()), String.valueOf(row.contractUuid()),
                String.valueOf(row.contractProjectUuid()), String.valueOf(row.contractConsultantUuid()),
                row.duration().setScale(6, java.math.RoundingMode.HALF_UP).toPlainString(),
                row.rate().setScale(6, java.math.RoundingMode.HALF_UP).toPlainString(), row.rateStatus()));
    }

    static String deliveryItemFingerprint(StoredDelivery row, String distribution) {
        return sha256(String.join("|", row.invoiceItemUuid(), row.itemHours().toPlainString(),
                row.itemRate().toPlainString(), row.itemOrigin()==null?"":row.itemOrigin(),
                row.itemRuleId()==null?"":row.itemRuleId(), distribution));
    }

    /** Converts only exact persisted evidence kinds; mixed or malformed evidence fails closed. */
    static PracticeRevenueAllocationService.SourceEvidence evidenceForStoredAttributions(
            List<StoredAttribution> stored) {
        if (stored == null || stored.isEmpty()) {
            return PracticeRevenueAllocationService.SourceEvidence.absent(
                    PracticeRevenueAllocationService.SourceTier.PERSISTED);
        }
        Set<String> kinds = stored.stream().map(StoredAttribution::source).filter(Objects::nonNull)
                .map(String::trim).map(String::toUpperCase).collect(java.util.stream.Collectors.toSet());
        boolean phantom = stored.stream().allMatch(value -> "PHANTOM".equals(value.documentType()));
        if (kinds.equals(Set.of("AUTO")) && !hasCoherentVersionedAutoProvenance(stored)) {
            return PracticeRevenueAllocationService.SourceEvidence.absent(
                    phantom ? PracticeRevenueAllocationService.SourceTier.PHANTOM_AUTO
                            : PracticeRevenueAllocationService.SourceTier.PERSISTED);
        }
        PracticeRevenueAllocationService.SourceTier tier;
        PracticeRevenueAllocationService.AttributionSource source;
        PracticeRevenueAllocationService.AttributionStatus status;
        if (kinds.size() != 1 || kinds.stream().anyMatch(String::isBlank)) {
            tier = kinds.contains("MANUAL") || kinds.contains("SELFBILLED_ASSIGNMENT")
                    ? PracticeRevenueAllocationService.SourceTier.HUMAN
                    : PracticeRevenueAllocationService.SourceTier.PERSISTED;
            return PracticeRevenueAllocationService.SourceEvidence.invalid(tier,
                    PracticeRevenueAllocationService.AttributionSource.NONE,
                    PracticeRevenueAllocationService.ReasonCode.CONTRADICTORY_EVIDENCE);
        }
        switch (kinds.iterator().next()) {
            case "MANUAL" -> {
                tier = phantom ? PracticeRevenueAllocationService.SourceTier.HUMAN
                        : PracticeRevenueAllocationService.SourceTier.PERSISTED;
                source = PracticeRevenueAllocationService.AttributionSource.PERSISTED_MANUAL;
                status = PracticeRevenueAllocationService.AttributionStatus.CONFIRMED;
            }
            case "SELFBILLED_ASSIGNMENT" -> {
                tier = PracticeRevenueAllocationService.SourceTier.HUMAN;
                source = PracticeRevenueAllocationService.AttributionSource.HUMAN;
                status = PracticeRevenueAllocationService.AttributionStatus.CONFIRMED;
            }
            case "AUTO" -> {
                tier = phantom ? PracticeRevenueAllocationService.SourceTier.PHANTOM_AUTO
                        : PracticeRevenueAllocationService.SourceTier.PERSISTED;
                source = phantom ? PracticeRevenueAllocationService.AttributionSource.PHANTOM_AUTO
                        : PracticeRevenueAllocationService.AttributionSource.PERSISTED_AUTO;
                status = PracticeRevenueAllocationService.AttributionStatus.ESTIMATED;
            }
            default -> {
                return PracticeRevenueAllocationService.SourceEvidence.invalid(
                        PracticeRevenueAllocationService.SourceTier.PERSISTED,
                        PracticeRevenueAllocationService.AttributionSource.NONE,
                        PracticeRevenueAllocationService.ReasonCode.ATTRIBUTION_INVALID);
            }
        }
        List<PracticeRevenueAllocationService.RecipientCandidate> recipients = new ArrayList<>(stored.size());
        for (StoredAttribution value : stored) {
            if (value.sharePct() == null || value.sharePct().signum() < 0
                    || value.sharePct().compareTo(new BigDecimal("100")) > 0) {
                return PracticeRevenueAllocationService.SourceEvidence.invalid(tier, source,
                        PracticeRevenueAllocationService.ReasonCode.ATTRIBUTION_INVALID);
            }
            PracticeRevenueAllocationService.ConsultantType type = switch (value.consultantType()) {
                case "EXTERNAL" -> PracticeRevenueAllocationService.ConsultantType.EXTERNAL;
                case null -> null;
                default -> PracticeRevenueAllocationService.ConsultantType.INTERNAL;
            };
            BigDecimal share = value.sharePct().divide(new BigDecimal("100"), 18,
                    java.math.RoundingMode.HALF_UP);
            recipients.add(new PracticeRevenueAllocationService.RecipientCandidate(value.uuid(),
                    value.consultantUuid(), value.practiceCode(), type, null, null, value.source(), share,
                    PracticeRevenueAllocationService.PracticeResolutionMethod.MONTH_END_PRACTICE,
                    "CURRENT_PRACTICE_FALLBACK".equals(value.attributionBasis()), false,
                    null, null, null, false));
        }
        return new PracticeRevenueAllocationService.SourceEvidence(tier,
                PracticeRevenueAllocationService.EvidenceState.PRESENT, source, status, true,
                recipients, PracticeRevenueAllocationService.ReasonCode.NONE);
    }

    private static boolean hasCoherentVersionedAutoProvenance(List<StoredAttribution> stored) {
        Set<String> algorithms = stored.stream().map(StoredAttribution::algorithmVersion)
                .filter(PracticeRevenueMaterializationService::notBlank)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> sourceKinds = stored.stream().map(StoredAttribution::sourceKind)
                .filter(PracticeRevenueMaterializationService::notBlank)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> fingerprints = stored.stream().map(StoredAttribution::dependencyFingerprint)
                .filter(PracticeRevenueMaterializationService::notBlank)
                .collect(java.util.stream.Collectors.toSet());
        return algorithms.equals(Set.of(AUTO_ATTRIBUTION_ALGORITHM_VERSION))
                && sourceKinds.equals(Set.of(AUTO_ATTRIBUTION_SOURCE_KIND))
                && fingerprints.size() == 1
                && stored.stream().allMatch(row -> notBlank(row.algorithmVersion())
                        && notBlank(row.sourceKind()) && notBlank(row.dependencyFingerprint()))
                && fingerprints.iterator().next().matches("[a-f0-9]{64}");
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }

    /** Narayana takes whole seconds; clamp to [1s, 24h] so a misconfigured Duration cannot disable the reaper. */
    static int transactionTimeoutSeconds(Duration configured) {
        return (int) Math.max(1, Math.min(configured.toSeconds(), 86_400));
    }

    void validate(BuildCandidate candidate) {
        Map<String,BigDecimal> allocated = new HashMap<>();
        for (AllocationEnvelope envelope : candidate.allocations()) {
            allocated.merge(envelope.itemControlKey(), envelope.result().allocatedControlDkk(), BigDecimal::add);
        }
        for (ItemEnvelope envelope : candidate.items()) {
            BigDecimal control = envelope.item().itemControlDkk();
            if (control != null && control.setScale(2, java.math.RoundingMode.HALF_UP)
                    .compareTo(allocated.getOrDefault(envelope.item().itemControlKey(), BigDecimal.ZERO).setScale(2)) != 0) {
                throw new StructuralValidationException("ITEM_ALLOCATION_CONSERVATION_FAILED");
            }
        }
        if (candidate.itemControlTotal().compareTo(candidate.allocationTotal()) != 0) {
            throw new StructuralValidationException("PORTFOLIO_CONSERVATION_FAILED");
        }
        if (candidate.reconciliationGap() != null) {
            // Section 8.3: reconcile within max(DKK 1.00, ABS(control_dkk) * 0.0001). The GL-controlled
            // subset total is the candidate control; a null gap means no GL-controlled subset exists.
            BigDecimal tolerance = new BigDecimal("1.00").max(
                    candidate.glControlTotal().abs().multiply(new BigDecimal("0.0001")));
            if (candidate.reconciliationGap().abs().compareTo(tolerance) > 0) {
                throw new StructuralValidationException("GL_RECONCILIATION_FAILED");
            }
        }
    }

    void persist(Attempt attempt, BuildCandidate candidate) {
        LocalDateTime now = candidate.snapshotAt();
        Map<String, PracticeRevenueAllocationService.AttributionStatus> attributionStatusByItem =
                candidate.allocations().stream().collect(java.util.stream.Collectors.toMap(
                        AllocationEnvelope::itemControlKey, envelope -> envelope.result().status()));
        for (ItemEnvelope envelope : candidate.items()) {
            var source = envelope.item(); var document = envelope.document();
            PracticeRevenueItem row = new PracticeRevenueItem();
            row.generationId=attempt.generationId(); row.itemControlKey=source.itemControlKey();
            row.rowKind=source.rowKind().name(); row.sourceDocumentUuid=source.sourceDocumentUuid();
            row.sourceItemUuid=source.sourceItemUuid(); row.companyUuid=envelope.companyUuid();
            row.sourceDocumentType=document.documentType().name(); row.sourceDocumentStatus=envelope.documentStatus();
            row.recognizedMonth=source.recognizedMonth(); row.itemCategory=source.itemCategory()==null?null:source.itemCategory().name();
            row.adjustmentSubtype=adjustment(source.adjustmentSubtype()); row.nativeCurrency=source.nativeCurrency();
            row.nativeItemAmount=source.nativeItemAmount(); row.documentSign=documentSign(document.documentType());
            row.signedNativeControl=source.signedNativeControl(); row.itemControlDkk=source.itemControlDkk();
            row.documentControlDkk=source.documentControlDkk(); row.documentGlRevenueDkk=source.rawGlControlDkk();
            row.itemCentAdjustmentDkk=source.glCentAdjustmentDkk(); row.effectiveDocumentRatio=source.effectiveDocumentRatio();
            row.documentRatioClosureRow=source.documentRatioClosureRow(); row.unroundedItemDkk=source.unroundedItemDkk();
            row.provisionalDocumentControlDkk=document.provisionalControlDkk(); row.dkkPerNativeUnit=source.dkkPerNativeUnit();
            row.centFloorDkk=source.floorItemDkk(); row.fractionalCentResidue=source.fractionalCentResidue();
            row.oneCentAwarded=source.oneCentAwarded(); row.fxNormalizationChanged=source.fxNormalizationChanged();
            row.matchedVoucherKey=document.matchedVoucherKey(); row.matchedGlRawDkk=document.matchedRawGlDkk();
            row.matchedGlCandidateCentDkk=document.matchedGlCandidateCentDkk(); row.controlSource=source.controlSource().name();
            // chk_fpnri_row_semantics allows residual_control_reason only on DOCUMENT_RESIDUAL rows;
            // companion SOURCE_ITEM rows disclose the same reason via validation_reason_code below.
            row.valuationStatus=source.valuationStatus().name();
            row.residualControlReason=source.rowKind()==PracticeRevenueValuationService.ItemRowKind.DOCUMENT_RESIDUAL
                    ?residual(source.reasonCode()):null;
            row.attributionSourceStatus=attributionStatusByItem.getOrDefault(source.itemControlKey(),
                    PracticeRevenueAllocationService.AttributionStatus.UNASSIGNED).name();
            applyCreditCopyEvidence(row, envelope.creditEvidence());
            var scope=envelope.evidenceScope();
            row.evidenceResolvedSegment=scope==null||scope.resolvedSegment()==null?null:scope.resolvedSegment().name();
            row.evidencePracticeBasis=scope==null?null:scope.practiceBasis();
            row.evidenceConsultantTypeBasis=scope==null?null:scope.consultantTypeBasis();
            row.scopeResolutionStatus=scope==null?null:scope.status().name();
            row.scopeResolutionReason=scope==null||scope.reason()==PracticeRevenueAllocationService.ReasonCode.NONE
                    ?null:scope.reason().name();
            row.duplicateRiskStatus=duplicateRiskStatus(source.reasonCode());
            row.syntheticResidual=source.syntheticResidual();
            row.sourceFingerprint=hash(source.toString(),String.valueOf(scope));
            row.validationReasonCode=source.reasonCode().name();
            row.createdAt=now; row.refreshedAt=now; em.persist(row);
        }
        for (AllocationEnvelope envelope : candidate.allocations()) for (var source : envelope.result().allocations()) {
            PracticeRevenueAllocation row = new PracticeRevenueAllocation();
            row.generationId=attempt.generationId(); row.itemControlKey=envelope.itemControlKey(); row.allocationSequence=source.sequence();
            row.consultantUuid=source.consultantUuid(); row.segmentId=source.segmentId().name(); row.effectivePracticeCode=source.effectivePracticeCode();
            row.effectivePracticeBasis=source.historicalPracticeFallback()?"CURRENT_PRACTICE_FALLBACK":"HISTORY";
            row.practiceResolutionMethod=source.practiceResolutionMethod()==PracticeRevenueAllocationService.PracticeResolutionMethod.NONE?null:source.practiceResolutionMethod().name();
            row.inheritedCreditResolution=source.inheritedCreditResolution(); row.sourceAllocationReference=source.sourceAllocationReference();
            row.sourceDependencyReference=source.sourceDependencyReference(); row.attributionSource=source.source().name();
            row.attributionStatus=source.status().name(); row.shareBeforeRounding=source.rawFraction(); row.rawFraction=source.rawFraction();
            row.effectiveNormalizedFraction=source.effectiveFraction(); row.rawShareSum=source.rawShareSum(); row.fractionClosureRow=source.closureRow();
            row.fractionNormalizationApplied=source.normalizationApplied(); row.contributingSourceIds=String.join(",",source.contributingSourceIds());
            row.unroundedAllocationDkk=source.unroundedAllocationDkk(); row.floorAllocationDkk=source.floorAllocationDkk();
            row.fractionalCentResidue=source.fractionalCentResidue(); row.oneCentAwarded=source.oneCentAwarded();
            row.allocationDkk=source.allocationDkk(); row.deliveryStartDate=source.deliveryStart();
            row.deliveryEndDate=source.deliveryEndExclusive(); row.historicalPracticeFallback=source.historicalPracticeFallback();
            row.residualReason=source.residualReason()==null?null:source.residualReason().name(); row.createdAt=now; row.refreshedAt=now; em.persist(row);
        }
        int sequence=0;
        for (DependencyEnvelope source : candidate.dependencies()) {
            PracticeRevenueDependency row = new PracticeRevenueDependency();
            row.generationId=attempt.generationId(); row.dependentItemControlKey=source.itemControlKey();
            row.dependencyKind=source.kind(); row.dependencyKey=source.key(); row.dependencySequence=sequence++;
            row.dependentRecognizedMonth=source.recognizedMonth(); row.dependencySourceCategory=source.sourceCategory();
            row.sourceDocumentUuid=source.sourceDocumentUuid(); row.sourceItemUuid=source.sourceItemUuid();
            row.sourceAttributionUuid=source.sourceAttributionUuid(); row.sourceWorkUuid=source.sourceWorkUuid();
            row.sourceUserUuid=source.sourceUserUuid(); row.sourceTaskUuid=source.sourceTaskUuid();
            row.sourceProjectUuid=source.sourceProjectUuid();
            row.sourceContractProjectUuid=source.sourceContractProjectUuid();
            row.sourceContractUuid=source.sourceContractUuid();
            row.sourceContractConsultantUuid=source.sourceContractConsultantUuid();
            row.sourceSelfBilledUuid=source.sourceSelfBilledUuid(); row.sourcePhantomUuid=source.sourcePhantomUuid();
            row.sourcePracticeBasisGenerationId=attempt.basisGenerationId();
            row.sourceCapacityUserUuid=source.sourceCapacityUserUuid();
            row.sourceCapacityStartDate=source.sourceCapacityStartDate();
            row.sourceCapacityEndDate=source.sourceCapacityEndDate();
            row.deliveryStartDate=source.deliveryStartDate(); row.deliveryEndDate=source.deliveryEndDate();
            row.bookedVoucherKey=source.bookedVoucherKey(); row.dependencyFingerprint=source.fingerprint();
            row.createdAt=now; em.persist(row);
        }
        em.flush();
    }

    BuildCandidate summarize(int documentCount, List<ItemEnvelope> items, List<AllocationEnvelope> allocations,
                             List<DependencyEnvelope> dependencies, LocalDate coverageStart, LocalDate coverageEnd,
                             Window booked, Window draft) {
        BigDecimal itemTotal=BigDecimal.ZERO.setScale(2), allocationTotal=BigDecimal.ZERO.setScale(2),
                gl=BigDecimal.ZERO.setScale(2), glAllocated=BigDecimal.ZERO.setScale(2);
        Set<String> glControlledItems = new HashSet<>();
        Set<String> duplicateRiskDocuments = new HashSet<>();
        int valued=0,missing=0,provisional=0,residual=0,confirmed=0,estimated=0,unassigned=0,glCount=0;
        for (ItemEnvelope item : items) {
            if (item.item().itemControlDkk()!=null) { valued++; itemTotal=itemTotal.add(item.item().itemControlDkk().setScale(2)); }
            else missing++;
            if (item.item().controlSource()==PracticeRevenueValuationService.ControlSource.ECONOMIC_GL && item.item().itemControlDkk()!=null) {
                gl=gl.add(item.item().itemControlDkk().setScale(2));
                glControlledItems.add(item.item().itemControlKey());
                glCount++;
            }
            if (item.item().controlSource()==PracticeRevenueValuationService.ControlSource.NATIVE_DKK
                    || item.item().controlSource()==PracticeRevenueValuationService.ControlSource.MONTHLY_FX) provisional++;
            if (item.item().syntheticResidual()) residual++;
            if (item.item().reasonCode()==PracticeRevenueValuationService.ReasonCode.MANUAL_PHANTOM_DUPLICATE_RISK) {
                duplicateRiskDocuments.add(item.item().sourceDocumentUuid());
            }
        }
        for (AllocationEnvelope envelope : allocations) for (var allocation : envelope.result().allocations()) {
            allocationTotal=allocationTotal.add(allocation.allocationDkk());
            if (glControlledItems.contains(envelope.itemControlKey())) {
                glAllocated=glAllocated.add(allocation.allocationDkk());
            }
            switch(allocation.status()) { case CONFIRMED -> confirmed++; case ESTIMATED -> estimated++; case UNASSIGNED -> unassigned++; }
        }
        BigDecimal glControl=glCount==0?null:gl;
        BigDecimal gap=glControl==null?null:glAllocated.subtract(glControl);
        return new BuildCandidate(LocalDateTime.now(ZoneOffset.UTC), coverageStart, coverageEnd, booked, draft,
                documentCount, items, allocations, dependencies, valued, missing, provisional, confirmed, estimated,
                unassigned, residual, duplicateRiskDocuments.size(), itemTotal, allocationTotal, glControl, gap);
    }

    static String duplicateRiskStatus(PracticeRevenueValuationService.ReasonCode reason) {
        return reason == PracticeRevenueValuationService.ReasonCode.MANUAL_PHANTOM_DUPLICATE_RISK
                ? "PHANTOM_DUPLICATE_RISK" : "NONE";
    }

    /** Section 4.1: the document sign is a property of the document type, never of an item's own value. */
    static Short documentSign(PracticeRevenueValuationService.DocumentType type){
        return switch(type){
            case CREDIT_NOTE -> (short)-1;
            case INVOICE, PHANTOM -> (short)1;
            default -> null;
        };
    }

    /**
     * Section 6.1: persist the immutable server-owned credit-copy proof exactly as it was loaded at
     * build time. Non-credit or no-proof rows stay NONE with null siblings; a non-NONE proof carries all
     * four sibling columns verbatim. Mutable source state is never re-read here.
     */
    static void applyCreditCopyEvidence(PracticeRevenueItem row, CreditEvidence proof){
        String kind = proof == null ? null : proof.copyKind();
        if(kind == null || "NONE".equals(kind)){
            row.creditCopyKind="NONE";
            return;
        }
        row.creditCopyKind=kind;
        row.creditCopyScope=proof.copyScope();
        row.creditCopyScale=proof.copyScale();
        row.creditCopyOriginalSourceNativeAmount=proof.originalSourceNativeAmount();
        row.creditCopyFingerprint=proof.copyFingerprint();
    }

    private void verifyAttempt(Attempt attempt) {
        Object[] row = (Object[]) nativeQuery("""
                SELECT r.status,r.owner_token,r.attempt_generation_id,r.shared_control_version,
                       r.paired_cost_generation_at,r.practice_basis_generation_id,
                       c.refresh_enabled,c.control_version,c.revenue_recovery_owner_token,
                       o.refresh_state,o.generation_at,o.practice_basis_generation_id,
                       o.certified_cost_basis_request_id,o.certified_cost_basis_request_vector,
                       b.full_refresh_version,b.incremental_refresh_version,
                       id.source_version,fg.source_version,cu.source_version,ac.source_version,
                       ia.source_version,sb.source_version,pa.source_version,de.source_version,pb.source_version
                FROM practice_revenue_publication r
                JOIN practice_contribution_publication_control c ON c.control_id=1
                JOIN practice_operating_cost_publication o ON o.publication_id=1
                JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
                JOIN practice_revenue_source_watermark id ON id.source_name='INVOICE_DOCUMENT'
                JOIN practice_revenue_source_watermark fg ON fg.source_name='FINANCE_GL'
                JOIN practice_revenue_source_watermark cu ON cu.source_name='CURRENCY'
                JOIN practice_revenue_source_watermark ac ON ac.source_name='ACCOUNT_CLASSIFICATION'
                JOIN practice_revenue_source_watermark ia ON ia.source_name='INVOICE_ATTRIBUTION'
                JOIN practice_revenue_source_watermark sb ON sb.source_name='SELF_BILLED'
                JOIN practice_revenue_source_watermark pa ON pa.source_name='PHANTOM_ATTRIBUTION'
                JOIN practice_revenue_source_watermark de ON de.source_name='DELIVERY_EVIDENCE'
                JOIN practice_revenue_source_watermark pb ON pb.source_name='PRACTICE_BASIS_INPUT'
                WHERE r.publication_key='PRACTICE_CONTRIBUTION'
                """).getSingleResult();
        boolean coherent="RUNNING".equals(text(row[0])) && attempt.ownerToken().equals(text(row[1]))
                && attempt.generationId().equals(text(row[2])) && attempt.controlVersion().equals(integer(row[3]))
                && attempt.costGenerationAt().equals(toDateTime(row[4])) && attempt.basisGenerationId().equals(text(row[5]))
                && bool(row[6]) && attempt.controlVersion().equals(integer(row[7])) && row[8]==null
                && "READY".equals(text(row[9])) && attempt.costGenerationAt().equals(toDateTime(row[10]))
                && attempt.basisGenerationId().equals(text(row[11])) && attempt.costRequestId().equals(integer(row[12]))
                && attempt.costRequestVector().equals(text(row[13])) && attempt.fullRefreshVersion().equals(integer(row[14]))
                && attempt.incrementalRefreshVersion().equals(integer(row[15]));
        for(int i=0;i<9;i++) coherent &= attempt.sourceVersions().get(PracticeRevenueDirtyMarker.Source.values()[i]).equals(integer(row[16+i]));
        if(!coherent) throw new PublicationConflictException("REVENUE_SOURCE_VECTOR_ADVANCED");
    }

    void failAndCleanup(Attempt attempt,String reason) {
        Object[] owner=(Object[])nativeQuery("SELECT status,owner_token,attempt_generation_id FROM practice_revenue_publication WHERE publication_key='PRACTICE_CONTRIBUTION' FOR UPDATE").getSingleResult();
        if(!"RUNNING".equals(text(owner[0]))||!attempt.ownerToken().equals(text(owner[1]))||!attempt.generationId().equals(text(owner[2]))) return;
        nativeQuery("DELETE FROM fact_practice_net_revenue_item_mat WHERE generation_id=:generation").setParameter("generation",attempt.generationId()).executeUpdate();
        nativeQuery("""
                UPDATE practice_revenue_publication SET status='FAILED',owner_token=NULL,attempt_generation_id=NULL,
                    failed_at=UTC_TIMESTAMP(6),failure_code=:reason,publication_version=publication_version+1
                WHERE publication_key='PRACTICE_CONTRIBUTION' AND status='RUNNING' AND owner_token=:owner AND attempt_generation_id=:generation
                """).setParameter("reason",reason).setParameter("owner",attempt.ownerToken()).setParameter("generation",attempt.generationId()).executeUpdate();
    }
    private void pruneOldGenerations(){nativeQuery("""
            DELETE i FROM fact_practice_net_revenue_item_mat i JOIN practice_revenue_publication p ON p.publication_key='PRACTICE_CONTRIBUTION'
            WHERE i.generation_id NOT IN (COALESCE(p.published_generation_id,''),COALESCE(p.previous_generation_id,''),COALESCE(p.attempt_generation_id,''))
            """).executeUpdate();}
    private boolean acquireLock(String name){return number(nativeQuery("SELECT GET_LOCK(:name,:seconds)").setParameter("name",name).setParameter("seconds",Math.toIntExact(lockWait.toSeconds())).getSingleResult()).intValue()==1;}
    private void releaseLock(String name){try{nativeQuery("SELECT RELEASE_LOCK(:name)").setParameter("name",name).getSingleResult();}catch(RuntimeException e){log.warnf(e,"could not release %s",name);}}

    Query nativeQuery(String sql){
        if(queryTimeout==null||queryTimeout.isZero()||queryTimeout.isNegative()
                ||queryTimeout.toMillis()>Integer.MAX_VALUE){
            throw new IllegalStateException("invalid practices contribution query timeout");
        }
        return em.createNativeQuery(sql).setHint("jakarta.persistence.query.timeout",
                Math.toIntExact(queryTimeout.toMillis()));
    }

    private static void requireNotInterrupted(){
        if(Thread.currentThread().isInterrupted()){
            throw new IllegalStateException("PRACTICE_REVENUE_JOB_INTERRUPTED");
        }
    }

    private static Window window(PracticeCostSnapshotProvider.CanonicalSnapshot response){
        boolean available=response.windowAvailable();
        return new Window(available,response.windowReason(),ym(response.reportingThroughMonthKey()),ym(response.currentPeriodStartMonthKey()),ym(response.currentPeriodEndMonthKey()),ym(response.priorPeriodStartMonthKey()),ym(response.priorPeriodEndMonthKey()));}
    private static LocalDate ym(String value){if(value==null)return null;return YearMonth.parse(value.substring(0,4)+"-"+value.substring(4,6)).atDay(1);}

    private static Map<String,List<DeliveryDependencySeed>> immutableDependencyMap(
            Map<String,List<DeliveryDependencySeed>> source){
        Map<String,List<DeliveryDependencySeed>> result=new HashMap<>();
        source.forEach((key,value)->result.put(key,List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static void addDeliveryDependencies(List<DependencyEnvelope> target,
                                                PracticeRevenueValuationService.ItemControl item,
                                                List<DeliveryDependencySeed> seeds,
                                                String basisGeneration){
        if(seeds==null||seeds.isEmpty())return;
        Set<String> existing=target.stream().filter(row->row.itemControlKey().equals(item.itemControlKey()))
                .map(row->row.sourceCategory()+"|"+row.kind()+"|"+row.key()+"|"+row.fingerprint())
                .collect(java.util.stream.Collectors.toSet());
        for(DeliveryDependencySeed seed:seeds){
            for(DependencyEnvelope dependency:DependencyEnvelope.delivery(item,seed,basisGeneration)){
                String key=dependency.sourceCategory()+"|"+dependency.kind()+"|"+dependency.key()+"|"
                        +dependency.fingerprint();
                if(existing.add(key))target.add(dependency);
            }
        }
    }
    private static String adjustment(PracticeRevenueValuationService.AdjustmentSubtype v){return v==null?null:v.name();}
    private static String residual(PracticeRevenueValuationService.ReasonCode reason){return switch(reason){case NEAR_ZERO_SIGNED_NATIVE_DENOMINATOR->"NEAR_ZERO_SIGNED_NATIVE_DENOMINATOR";case HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE->"HEADER_DISCOUNT_MONETARY_STRUCTURE_UNAVAILABLE";default->null;};}
    private static PracticeRevenueValuationService.ItemOrigin itemOrigin(Object value){try{return PracticeRevenueValuationService.ItemOrigin.valueOf(text(value));}catch(Exception e){return PracticeRevenueValuationService.ItemOrigin.UNKNOWN;}}
    private static PracticeRevenueValuationService.DocumentType documentType(Object value){try{return PracticeRevenueValuationService.DocumentType.valueOf(text(value));}catch(Exception e){return PracticeRevenueValuationService.DocumentType.OTHER;}}
    private static String safeFailure(Throwable value){return value instanceof StructuralValidationException?"STRUCTURAL_VALIDATION_FAILED":value instanceof PublicationConflictException?"PUBLICATION_PRECONDITION_FAILED":"MATERIALIZATION_FAILED";}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hash(String... values){try{MessageDigest d=MessageDigest.getInstance("SHA-256");for(String v:values){d.update(String.valueOf(v).getBytes(StandardCharsets.UTF_8));d.update((byte)0);}return HexFormat.of().formatHex(d.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static Number number(Object value){return (Number)value;} private static String text(Object value){return value==null?null:String.valueOf(value);} private static boolean bool(Object value){return value instanceof Boolean b?b:number(value).intValue()!=0;}
    private static BigInteger integer(Object value){return value instanceof BigInteger i?i:new BigInteger(value.toString());} private static BigDecimal decimalOrNull(Object value){return value==null?null:value instanceof BigDecimal d?d:new BigDecimal(value.toString());}
    private static long positiveLong(Object value){if(value==null)return 0;long parsed=number(value).longValue();return Math.max(0,parsed);}
    /**
     * V338's {@code economics_accounting_year} is VARCHAR(20) holding e-conomic's "2025/2026" form;
     * the identifier is its leading fiscal-start year. Null/blank/non-positive is absent (0). A
     * present-but-unparseable value returns -1 so the cross-year guard fails closed to AMBIGUOUS
     * rather than crashing the refresh or silently matching.
     */
    static int accountingYearStart(Object value){
        if(value==null)return 0;
        if(value instanceof Number parsed)return Math.max(0,parsed.intValue());
        String raw=String.valueOf(value).trim();
        if(raw.isEmpty())return 0;
        java.util.regex.Matcher leadingYear=java.util.regex.Pattern.compile("^(\\d{4})(\\D.*)?$").matcher(raw);
        if(!leadingYear.matches())return -1;
        return Math.max(0,Integer.parseInt(leadingYear.group(1)));
    }
    private static BigDecimal deliveryOperand(Object value){return value==null?null:BigDecimal.valueOf(number(value).doubleValue()).setScale(6,java.math.RoundingMode.HALF_UP);}
    private static LocalDate toDate(Object value){return value instanceof LocalDate d?d:((java.sql.Date)value).toLocalDate();} private static LocalDateTime toDateTime(Object value){return value instanceof LocalDateTime d?d:((java.sql.Timestamp)value).toLocalDateTime();}
    private static Map<PracticeRevenueDirtyMarker.Source,BigInteger> vector(Object[] row,int offset){Map<PracticeRevenueDirtyMarker.Source,BigInteger> result=new EnumMap<>(PracticeRevenueDirtyMarker.Source.class);for(int i=0;i<9;i++)result.put(PracticeRevenueDirtyMarker.Source.values()[i],integer(row[offset+i]));return Map.copyOf(result);}

    private static final class MutableDocument{
        final String uuid,companyUuid,status,currency,discount;
        final PracticeRevenueValuationService.DocumentType type;
        final LocalDate date;
        final long economicsBookedNumber,referenceNumber,economicsVoucherNumber,economicsEntryNumber;
        final int economicsAccountingYear;
        final boolean internalDebtorCredit;
        final List<PracticeRevenueValuationService.ItemInput> items=new ArrayList<>();
        final List<PracticeRevenueValuationService.GlEntry> glEntries=new ArrayList<>();
        final List<String> fxRates=new ArrayList<>();
        boolean glEvidenceConflict;
        boolean recognized;
        MutableDocument(Object[]r){
            uuid=text(r[0]);companyUuid=text(r[1]);type=documentType(r[2]);status=text(r[3]);
            date=toDate(r[4]);currency=text(r[5]);discount=text(r[6]);
            economicsBookedNumber=positiveLong(r[23]);referenceNumber=positiveLong(r[24]);
            economicsVoucherNumber=positiveLong(r[25]);economicsEntryNumber=positiveLong(r[26]);
            economicsAccountingYear=accountingYearStart(r[27]);
            internalDebtorCredit=type==PracticeRevenueValuationService.DocumentType.CREDIT_NOTE && r[28]!=null;
        }
        PracticeRevenueValuationService.DocumentInput toInput(){
            return toInput(false);
        }
        PracticeRevenueValuationService.DocumentInput toInput(boolean dependencyOnly){
            return new PracticeRevenueValuationService.DocumentInput(uuid,companyUuid,type,status,
                    internalDebtorCredit,date,currency,discount,items,glEntries,fxRates,dependencyOnly,
                    glEvidenceConflict);
        }
        BigDecimal normalizedNativeControl(){
            try{
                BigDecimal total=BigDecimal.ZERO.setScale(12);
                for(var item:items){
                    BigDecimal hours=new BigDecimal(item.hoursText()).setScale(6,java.math.RoundingMode.HALF_UP);
                    BigDecimal rate=new BigDecimal(item.rateText()).setScale(6,java.math.RoundingMode.HALF_UP);
                    total=total.add(hours.multiply(rate).setScale(12));
                }
                return total;
            }catch(RuntimeException invalid){return null;}
        }
    }
    record CostCertificate(String status,BigInteger fullRefreshVersion,BigInteger incrementalRefreshVersion,
                           BigInteger practiceBasisInputVersion,
                           BigInteger financeGlVersion,BigInteger accountClassificationVersion,
                           String inputVectorFingerprint){
        boolean matches(BigInteger full,BigInteger incremental,BigInteger basis,BigInteger finance,
                        BigInteger classification,String vector){
            return ("READY".equals(status)||"NO_CHANGE".equals(status))
                    && fullRefreshVersion.equals(full) && incrementalRefreshVersion.equals(incremental)
                    && practiceBasisInputVersion.equals(basis)
                    && financeGlVersion.equals(finance) && accountClassificationVersion.equals(classification)
                    && inputVectorFingerprint.equals(vector);
        }
    }
    record StoredAttribution(String uuid,String consultantUuid,BigDecimal sharePct,String source,
                             String practiceCode,String consultantType,String attributionBasis,
                             String documentType,String algorithmVersion,String sourceKind,
                             String dependencyFingerprint){}
    record StoredSelfBilledAssignment(String itemUuid,String assignmentUuid,String consultantUuid,
                                      int workYear,int workMonth,BigDecimal shareAmount,String source,
                                      String practiceCode,String consultantType,String attributionBasis,
                                      BigDecimal itemNativeAmount,BigDecimal voucherNativeAmount){}
    record StoredDelivery(String invoiceItemUuid,String workUuid,String registrantUuid,
                          String effectiveConsultantUuid,LocalDate deliveryDate,String taskUuid,
                          String projectUuid,String contractUuid,String contractProjectUuid,
                          String contractConsultantUuid,BigDecimal duration,BigDecimal rate,
                          BigDecimal deliveryValue,String rateStatus,String algorithmVersion,
                          String itemFingerprint,String distributionFingerprint,BigDecimal itemHours,
                          BigDecimal itemRate,String itemOrigin,String itemRuleId,String practiceCode,
                          String consultantType,String attributionBasis){}
    record StoredGl(String companyUuid,int financialYearStart,long voucherNumber,long journalNumber,
                    String amountText,String accountingIdentifier,long invoiceNumber,long entryNumber){
        String groupKey(){return companyUuid+":"+financialYearStart+":BOOKED:"+voucherNumber;}
    }
    record GlSelection(boolean conflict,List<StoredGl> rows){GlSelection{rows=List.copyOf(rows);}}
    record CreditEvidence(String itemUuid,String sourceItemUuid,String copyKind,String copyScope,
                          BigDecimal copyScale,BigDecimal originalSourceNativeAmount,String copyFingerprint,
                          String itemDocumentUuid,String linkedSourceDocumentUuid,
                          BigDecimal itemHours,BigDecimal itemRate,String itemOrigin,
                          String consultantUuid,String calculationRef,String itemRuleId,
                          String pricingInputFingerprint,String pricingOutputFingerprint){}
    record BaseRecipientKey(String consultantUuid,PracticeRevenueAllocationService.SegmentId segment,
                            String practiceCode,
                            PracticeRevenueAllocationService.PracticeResolutionMethod practiceResolutionMethod,
                            boolean historicalPracticeFallback,LocalDate deliveryStart,
                            LocalDate deliveryEndExclusive,String attributionSourceType,
                            PracticeRevenueAllocationService.AttributionStatus status){}
    static final class BaseRecipientWeight{
        BigDecimal amount=BigDecimal.ZERO;
        final List<String> sourceIds=new ArrayList<>();
        void add(BigDecimal value,String sourceId){amount=amount.add(value);sourceIds.add(sourceId);}
    }
    record Attempt(String generationId,String ownerToken,BigInteger controlVersion,LocalDateTime costGenerationAt,
                   String basisGenerationId,BigInteger costRequestId,String costRequestVector,
                   BigInteger fullRefreshVersion,BigInteger incrementalRefreshVersion,
                   Map<PracticeRevenueDirtyMarker.Source,BigInteger> sourceVersions){}
    record ItemEnvelope(String companyUuid,String documentStatus,
                        PracticeRevenueValuationService.ItemControl item,
                        PracticeRevenueValuationService.DocumentValuation document,
                        PracticeRevenueAllocationService.EvidenceScope evidenceScope,
                        CreditEvidence creditEvidence){
        ItemEnvelope(String companyUuid,String documentStatus,
                     PracticeRevenueValuationService.ItemControl item,
                     PracticeRevenueValuationService.DocumentValuation document){
            this(companyUuid,documentStatus,item,document,null,null);
        }
        ItemEnvelope(String companyUuid,String documentStatus,
                     PracticeRevenueValuationService.ItemControl item,
                     PracticeRevenueValuationService.DocumentValuation document,
                     PracticeRevenueAllocationService.EvidenceScope evidenceScope){
            this(companyUuid,documentStatus,item,document,evidenceScope,null);
        }
    }
    record AllocationEnvelope(String itemControlKey,PracticeRevenueAllocationService.AllocationResult result){}
    record DeliveryEvidenceBundle(Map<String,PracticeRevenueAllocationService.SourceEvidence> evidence,
                                  Map<String,List<DeliveryDependencySeed>> dependencies){
        DeliveryEvidenceBundle{evidence=Map.copyOf(evidence);dependencies=Map.copyOf(dependencies);}
        /**
         * Map.copyOf produces a JDK immutable map whose get(null) throws instead of returning null.
         * Sentinel rows (zero-item documents, generated residuals) legitimately carry a null
         * source-item UUID and simply have no item-level delivery evidence.
         */
        PracticeRevenueAllocationService.SourceEvidence evidenceFor(String key){
            return key==null?null:evidence.get(key);
        }
        List<DeliveryDependencySeed> dependenciesFor(String key){
            return key==null?List.of():dependencies.getOrDefault(key,List.of());
        }
    }
    record DeliveryDependencySeed(String workUuid,String registrantUuid,String effectiveConsultantUuid,
                                  LocalDate deliveryStart,LocalDate deliveryEndExclusive,String taskUuid,
                                  String projectUuid,String contractProjectUuid,String contractUuid,
                                  String contractConsultantUuid,LocalDate capacityStart,
                                  LocalDate capacityEndExclusive){}
    record DependencyEnvelope(String itemControlKey,String kind,String key,LocalDate recognizedMonth,
                              String sourceCategory,String sourceDocumentUuid,String sourceItemUuid,
                              String sourceAttributionUuid,String sourceWorkUuid,String sourceUserUuid,
                              String sourceTaskUuid,String sourceProjectUuid,String sourceContractProjectUuid,
                              String sourceContractUuid,String sourceContractConsultantUuid,
                              String sourceSelfBilledUuid,String sourcePhantomUuid,
                              String sourceCapacityUserUuid,LocalDate sourceCapacityStartDate,
                              LocalDate sourceCapacityEndDate,LocalDate deliveryStartDate,
                              LocalDate deliveryEndDate,String bookedVoucherKey,String fingerprint){
        static DependencyEnvelope document(PracticeRevenueValuationService.ItemControl i,String basis){
            return new DependencyEnvelope(i.itemControlKey(),"SOURCE_DOCUMENT",i.sourceDocumentUuid(),
                    i.recognizedMonth(),"INVOICE_DOCUMENT",i.sourceDocumentUuid(),i.sourceItemUuid(),
                    null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,
                    hash(i.sourceDocumentUuid(),String.valueOf(i.sourceItemUuid()),basis));
        }
        static DependencyEnvelope credit(PracticeRevenueValuationService.ItemControl i,String sourceDocument,
                                         String sourceItem,String basis){
            return new DependencyEnvelope(i.itemControlKey(),"CREDIT_SOURCE_ITEM",sourceItem,
                    i.recognizedMonth(),"INVOICE_DOCUMENT",sourceDocument,sourceItem,null,null,null,
                    null,null,null,null,null,null,null,null,null,null,null,null,null,
                    hash(sourceDocument,sourceItem,basis));
        }
        static List<DependencyEnvelope> delivery(PracticeRevenueValuationService.ItemControl item,
                                                 DeliveryDependencySeed seed,String basis){
            Map<String,String> keys=new LinkedHashMap<>();
            if(seed.workUuid()!=null)keys.put("WORK",seed.workUuid());
            keys.put("DELIVERY_INTERVAL",hash(String.valueOf(seed.registrantUuid()),
                    String.valueOf(seed.deliveryStart()),String.valueOf(seed.deliveryEndExclusive()),
                    String.valueOf(seed.projectUuid()),String.valueOf(seed.contractUuid())));
            if(seed.taskUuid()!=null)keys.put("TASK",seed.taskUuid());
            if(seed.projectUuid()!=null)keys.put("PROJECT",seed.projectUuid());
            if(seed.contractProjectUuid()!=null)keys.put("CONTRACT_PROJECT",seed.contractProjectUuid());
            if(seed.contractUuid()!=null)keys.put("CONTRACT",seed.contractUuid());
            if(seed.contractConsultantUuid()!=null)
                keys.put("CONTRACT_CONSULTANT",seed.contractConsultantUuid());
            List<DependencyEnvelope> result=new ArrayList<>();
            keys.forEach((kind,key)->result.add(ofDelivery(item,seed,basis,kind,key,"DELIVERY_EVIDENCE")));
            if(seed.effectiveConsultantUuid()!=null){
                String key=hash(seed.effectiveConsultantUuid(),String.valueOf(seed.capacityStart()),
                        String.valueOf(seed.capacityEndExclusive()),basis);
                result.add(ofDelivery(item,seed,basis,"PRACTICE_BASIS",key,"PRACTICE_BASIS_INPUT"));
            }
            return List.copyOf(result);
        }
        private static DependencyEnvelope ofDelivery(PracticeRevenueValuationService.ItemControl item,
                                                      DeliveryDependencySeed seed,String basis,
                                                      String kind,String key,String category){
            String fingerprint=hash(item.itemControlKey(),kind,key,String.valueOf(seed.workUuid()),
                    String.valueOf(seed.registrantUuid()),String.valueOf(seed.effectiveConsultantUuid()),
                    String.valueOf(seed.deliveryStart()),String.valueOf(seed.deliveryEndExclusive()),
                    String.valueOf(seed.taskUuid()),String.valueOf(seed.projectUuid()),
                    String.valueOf(seed.contractProjectUuid()),String.valueOf(seed.contractUuid()),
                    String.valueOf(seed.contractConsultantUuid()),String.valueOf(seed.capacityStart()),
                    String.valueOf(seed.capacityEndExclusive()),basis);
            return new DependencyEnvelope(item.itemControlKey(),kind,key,item.recognizedMonth(),category,
                    item.sourceDocumentUuid(),item.sourceItemUuid(),null,seed.workUuid(),
                    seed.registrantUuid(),seed.taskUuid(),seed.projectUuid(),seed.contractProjectUuid(),
                    seed.contractUuid(),seed.contractConsultantUuid(),null,null,
                    seed.effectiveConsultantUuid(),seed.capacityStart(),seed.capacityEndExclusive(),
                    seed.deliveryStart(),seed.deliveryEndExclusive(),null,fingerprint);
        }
    }
    record RevenueCoverage(YearMonth first,YearMonth last){}
    record Window(boolean available,String reason,LocalDate anchor,LocalDate currentStart,LocalDate currentEnd,LocalDate priorStart,LocalDate priorEnd){}
    record BuildCandidate(LocalDateTime snapshotAt,LocalDate coverageStart,LocalDate coverageEnd,Window booked,Window bookedPlusDraft,int documentCount,List<ItemEnvelope> items,List<AllocationEnvelope> allocations,List<DependencyEnvelope> dependencies,int valuedItemCount,int missingControlCount,int provisionalControlCount,int confirmedAttributionCount,int estimatedAttributionCount,int unassignedAllocationCount,int residualControlCount,int duplicateRiskCount,BigDecimal itemControlTotal,BigDecimal allocationTotal,BigDecimal glControlTotal,BigDecimal reconciliationGap){BuildCandidate{items=List.copyOf(items);allocations=List.copyOf(allocations);dependencies=List.copyOf(dependencies);}}
    public record Result(boolean started,String generationId,String status,int itemCount,int allocationCount){static Result notStarted(){return new Result(false,null,"NOT_STARTED",0,0);}}
    public static class PublicationConflictException extends IllegalStateException{public PublicationConflictException(String m){super(m);}}
    public static class StructuralValidationException extends IllegalStateException{public StructuralValidationException(String m){super(m);}}
    /** A consumed dependency date fell outside the certified basis coverage: fail closed and escalate. */
    public static class RevenueBasisCoverageMissException extends PublicationConflictException{
        private final transient LocalDate affectedStart;
        private final transient LocalDate affectedEnd;
        public RevenueBasisCoverageMissException(LocalDate affectedStart,LocalDate affectedEnd){
            super("BASIS_COVERAGE_MISS");this.affectedStart=affectedStart;this.affectedEnd=affectedEnd;
        }
        public LocalDate affectedStart(){return affectedStart;}
        public LocalDate affectedEnd(){return affectedEnd;}
    }
}
