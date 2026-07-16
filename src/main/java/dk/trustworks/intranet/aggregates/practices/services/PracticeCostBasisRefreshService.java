package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/** Single-writer coordinator for immutable effective-practice/cost generations. */
@JBossLog
@ApplicationScoped
public class PracticeCostBasisRefreshService {
    static final String LOCK_NAME = "practice_cost_basis";
    static final String PUBLICATION_KEY = "PRACTICE_CONTRIBUTION";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    @Inject EntityManager em;
    @Inject PracticeRevenueDependencyManifestProvider manifestProvider;
    @Inject PracticeBasisMaterializationService basisMaterializer;
    @Inject PracticeCostSnapshotProvider costSnapshotProvider;
    private final PracticeCostCandidateBuilder costCandidateBuilder = new PracticeCostCandidateBuilder();

    @ConfigProperty(name = "practices.contribution.named-lock-wait", defaultValue = "PT30S")
    Duration lockWait;

    /**
     * Claims only the request identity carried by the job execution. This closes the interval
     * between the caller's eligibility check and the batchlet transaction: a newer request can
     * never be substituted for the validated request.
     */
    public Outcome refreshExact(ExpectedRequest expected) {
        Objects.requireNonNull(expected, "expected request is required");
        Claim claim = QuarkusTransaction.requiringNew().call(() -> claimExpectedPending(expected));
        if (claim == null) {
            throw new PublicationConflictException("EXPECTED_COST_REQUEST_NOT_CLAIMABLE");
        }
        try {
            return QuarkusTransaction.requiringNew().call(() -> buildAndPublish(claim));
        } catch (DependencyManifestMissException miss) {
            // Fail closed on a manifest/coverage miss: durably advance the monotonic manifest input
            // version, enqueue the successor DEPENDENCY_MANIFEST_INPUT request, then retire this request
            // as SUPERSEDED pointing at that successor. Both steps commit independently of the aborted build.
            try {
                BigInteger successor = QuarkusTransaction.requiringNew()
                        .call(() -> escalateDependencyManifestMiss(miss));
                QuarkusTransaction.requiringNew().run(() -> supersedeAndCleanup(claim, successor));
            } catch (RuntimeException cleanupFailure) {
                miss.addSuppressed(cleanupFailure);
            }
            throw miss;
        } catch (CostRequestSupersededException superseded) {
            // A newer covering input displaced this owned RUNNING request. Retire the request as
            // SUPERSEDED pointing at its successor and drop only the unpublished candidate; this is
            // deliberately not a technical FAILED so successor-following and retention stay coherent.
            try {
                QuarkusTransaction.requiringNew().run(
                        () -> supersedeAndCleanup(claim, superseded.successorRequestId()));
            } catch (RuntimeException cleanupFailure) {
                superseded.addSuppressed(cleanupFailure);
            }
            throw superseded;
        } catch (RuntimeException failure) {
            try {
                QuarkusTransaction.requiringNew().run(() -> failAndCleanup(claim, "COST_BASIS_BUILD_FAILED"));
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    Claim claimExpectedPending(ExpectedRequest expected) {
        Object recovery = em.createNativeQuery("""
                SELECT revenue_recovery_owner_token
                FROM practice_contribution_publication_control WHERE control_id = 1 FOR UPDATE
                """).getSingleResult();
        Object[] control = (Object[]) em.createNativeQuery("""
                SELECT refresh_enabled, control_version
                FROM practice_contribution_publication_control WHERE control_id = 1
                """).getSingleResult();
        if (!bool(control[0]) || recovery != null) return null;

        @SuppressWarnings("unchecked")
        List<Object[]> requests = em.createNativeQuery("""
                SELECT request_id, request_key, cause, input_vector_fingerprint,
                       expected_full_refresh_version, expected_incremental_refresh_version,
                       expected_practice_basis_input_version, expected_finance_gl_version,
                       expected_account_classification_version, dependency_fingerprint
                FROM practice_cost_basis_refresh_request r
                JOIN practice_operating_cost_publication p
                  ON p.publication_id=1
                 AND p.latest_cost_basis_request_id=r.request_id
                 AND p.latest_cost_basis_request_vector=r.input_vector_fingerprint
                WHERE r.request_id=:requestId
                  AND r.request_key=:requestKey
                  AND r.input_vector_fingerprint=:inputVector
                  AND r.status='PENDING'
                  AND NOT EXISTS (
                      SELECT 1 FROM practice_cost_basis_refresh_request newer
                      WHERE newer.request_id>r.request_id
                  )
                FOR UPDATE
                """).setParameter("requestId", expected.requestId())
                .setParameter("requestKey", expected.requestKey())
                .setParameter("inputVector", expected.inputVectorFingerprint())
                .getResultList();
        if (requests.isEmpty()) return null;
        Object[] row = requests.getFirst();
        BigInteger id = integer(row[0]);
        String owner = UUID.randomUUID().toString();
        int updated = em.createNativeQuery("""
                UPDATE practice_cost_basis_refresh_request
                SET status = 'RUNNING', owner_token = :owner, claimed_at = UTC_TIMESTAMP(6),
                    attempt_count = attempt_count + 1, optimistic_version = optimistic_version + 1
                WHERE request_id = :id AND request_key=:requestKey
                  AND input_vector_fingerprint=:inputVector
                  AND status = 'PENDING' AND owner_token IS NULL
                """).setParameter("owner", owner).setParameter("id", id)
                .setParameter("requestKey", expected.requestKey())
                .setParameter("inputVector", expected.inputVectorFingerprint()).executeUpdate();
        if (updated != 1) return null;
        String capturedDependencyFingerprint = row.length > 9 ? text(row[9]) : null;
        return new Claim(id, text(row[1]), text(row[2]), text(row[3]), integer(row[4]), integer(row[5]),
                integer(row[6]), integer(row[7]), integer(row[8]), capturedDependencyFingerprint,
                integer(control[1]), owner);
    }

    Outcome buildAndPublish(Claim claim) {
        boolean locked = acquireLock(LOCK_NAME);
        if (!locked) throw new PublicationConflictException("COST_BASIS_LOCK_TIMEOUT");
        String basisGenerationId = UUID.randomUUID().toString();
        try {
            verifyClaimVector(claim);
            YearMonth lastMonth = YearMonth.from(
                    LocalDate.now(UtilizationCalculationHelper.REPORTING_ZONE)).minusMonths(1);
            YearMonth firstMonth = lastMonth.minusMonths(59);
            var manifest = manifestProvider.scan(firstMonth, lastMonth);
            var users = loadBasisUsers(manifest.coverageStart(), manifest.coverageEnd());
            var basis = basisMaterializer.materialize(new PracticeBasisMaterializationService.BuildInput(
                    basisGenerationId, manifest, claim.fullRefreshVersion(), claim.incrementalRefreshVersion(),
                    claim.practiceBasisInputVersion(), users));

            // Certification equality (design §10 point 4): when a request captured a document-dependency
            // fingerprint before the claim, the owner's recomputed manifest must match it exactly. Drift
            // proves a dependency source moved between enqueue and owned recomputation: fail closed and
            // escalate a fresh DEPENDENCY_MANIFEST_INPUT version rather than certifying against stale bounds.
            if (claim.capturedDependencyFingerprint() != null
                    && !claim.capturedDependencyFingerprint().equals(basis.dependencyManifestFingerprint())) {
                deleteBasisCandidate(basisGenerationId);
                throw new DependencyManifestMissException(manifest.coverageStart(), manifest.coverageEnd(),
                        basis.dependencyManifestFingerprint());
            }

            CostCandidatePersistence candidate = buildAndPersistCostCandidate(
                    basisGenerationId, manifest.coverageStart(), manifest.coverageEnd());
            java.time.Instant candidateSnapshotAt = java.time.Instant.now();
            PracticeCostSnapshotProvider.CanonicalWindow bookedWindow = PracticeCostSnapshotProvider.windowOf(
                    costSnapshotProvider.loadCandidateSnapshot(
                            dk.trustworks.intranet.financeservice.model.enums.CostSource.BOOKED,
                            basisGenerationId, candidateSnapshotAt, manifest.coverageStart()));
            PracticeCostSnapshotProvider.CanonicalWindow bookedPlusDraftWindow = PracticeCostSnapshotProvider.windowOf(
                    costSnapshotProvider.loadCandidateSnapshot(
                            dk.trustworks.intranet.financeservice.model.enums.CostSource.BOOKED_PLUS_DRAFT,
                            basisGenerationId, candidateSnapshotAt, manifest.coverageStart()));
            String contentFingerprint = hash(basis.sourceFingerprint(), basis.capacityFingerprint(),
                    basis.dependencyManifestFingerprint(), candidate.contentFingerprint());

            Object[] publication = (Object[]) em.createNativeQuery("""
                    SELECT latest_cost_basis_request_id, latest_cost_basis_request_vector,
                           cost_content_fingerprint, generation_at, practice_basis_generation_id,
                           publication_version
                    FROM practice_operating_cost_publication WHERE publication_id = 1 FOR UPDATE
                    """).getSingleResult();
            verifyFinalControl(claim, publication);

            boolean noChange = shouldCertifyNoChange(claim.cause(), contentFingerprint, text(publication[2]));
            if (noChange) {
                Query recertifyQuery = em.createNativeQuery("""
                        UPDATE practice_operating_cost_publication p
                        JOIN practice_contribution_publication_control c ON c.control_id=1
                        JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
                        JOIN practice_revenue_source_watermark pb ON pb.source_name='PRACTICE_BASIS_INPUT'
                        JOIN practice_revenue_source_watermark gl ON gl.source_name='FINANCE_GL'
                        JOIN practice_revenue_source_watermark ac ON ac.source_name='ACCOUNT_CLASSIFICATION'
                        SET p.certified_cost_basis_request_id=:requestId,
                            p.certified_cost_basis_request_vector=:vector,
                            p.booked_available=:bookedAvailable,
                            p.booked_reason=:bookedReason,
                            p.booked_anchor_month=:bookedAnchor,
                            p.booked_current_start_month=:bookedCurrentStart,
                            p.booked_current_end_month=:bookedCurrentEnd,
                            p.booked_prior_start_month=:bookedPriorStart,
                            p.booked_prior_end_month=:bookedPriorEnd,
                            p.booked_plus_draft_available=:draftAvailable,
                            p.booked_plus_draft_reason=:draftReason,
                            p.booked_plus_draft_anchor_month=:draftAnchor,
                            p.booked_plus_draft_current_start_month=:draftCurrentStart,
                            p.booked_plus_draft_current_end_month=:draftCurrentEnd,
                            p.booked_plus_draft_prior_start_month=:draftPriorStart,
                            p.booked_plus_draft_prior_end_month=:draftPriorEnd,
                            p.publication_version=p.publication_version+1
                        WHERE p.publication_id=1
                          AND p.latest_cost_basis_request_id=:requestId
                          AND p.latest_cost_basis_request_vector=:vector
                          AND p.publication_version=:publicationVersion
                          AND p.refresh_state='READY'
                          AND b.full_refresh_version=:fullRefreshVersion
                          AND b.incremental_refresh_version=:incrementalRefreshVersion
                          AND pb.source_version=:practiceBasisInputVersion
                          AND gl.source_version=:financeGlVersion
                          AND ac.source_version=:accountClassificationVersion
                          AND c.refresh_enabled=TRUE AND c.control_version=:controlVersion
                          AND c.revenue_recovery_owner_token IS NULL
                        """);
                bindWindowParameters(recertifyQuery, "booked", bookedWindow);
                bindWindowParameters(recertifyQuery, "draft", bookedPlusDraftWindow);
                int recertified = recertifyQuery.setParameter("requestId", claim.requestId())
                        .setParameter("vector", claim.inputVector())
                        .setParameter("publicationVersion", integer(publication[5]))
                        .setParameter("fullRefreshVersion", claim.fullRefreshVersion())
                        .setParameter("incrementalRefreshVersion", claim.incrementalRefreshVersion())
                        .setParameter("practiceBasisInputVersion", claim.practiceBasisInputVersion())
                        .setParameter("financeGlVersion", claim.financeGlVersion())
                        .setParameter("accountClassificationVersion", claim.accountClassificationVersion())
                        .setParameter("controlVersion", claim.controlVersion()).executeUpdate();
                if (recertified != 1) {
                    throw new PublicationConflictException("COST_RECERTIFICATION_CAS_FAILED");
                }
                int requestUpdated = terminalRequestUpdate(claim, "NO_CHANGE", null,
                        toLocalDateTime(publication[3]), text(publication[4]), contentFingerprint,
                        "BYTE_EQUIVALENT", basis.dependencyManifestFingerprint());
                if (requestUpdated != 1) throw new PublicationConflictException("REQUEST_OWNER_LOST");
                deleteBasisCandidate(basisGenerationId);
                return new Outcome(claim.requestId(), "NO_CHANGE", null, text(publication[4]), contentFingerprint);
            }

            LocalDateTime generationAt = LocalDateTime.now(ZoneOffset.UTC);
            Query publishQuery = em.createNativeQuery("""
                    UPDATE practice_operating_cost_publication p
                    JOIN practice_contribution_publication_control c ON c.control_id = 1
                    JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
                    JOIN practice_revenue_source_watermark pb ON pb.source_name='PRACTICE_BASIS_INPUT'
                    JOIN practice_revenue_source_watermark gl ON gl.source_name='FINANCE_GL'
                    JOIN practice_revenue_source_watermark ac ON ac.source_name='ACCOUNT_CLASSIFICATION'
                    SET p.refresh_state = 'READY', p.active_refresh_token = NULL,
                        p.generation_at = :generation, p.published_at = UTC_TIMESTAMP(6),
                        p.opex_row_count = :opexRows, p.fte_row_count = :fteRows,
                        p.completeness_row_count = :completenessRows,
                        p.practice_basis_generation_id = :basis,
                        p.certified_cost_basis_request_id = :requestId,
                        p.certified_cost_basis_request_vector = :vector,
                        p.cost_content_fingerprint = :fingerprint,
                        p.booked_available=:bookedAvailable,
                        p.booked_reason=:bookedReason,
                        p.booked_anchor_month=:bookedAnchor,
                        p.booked_current_start_month=:bookedCurrentStart,
                        p.booked_current_end_month=:bookedCurrentEnd,
                        p.booked_prior_start_month=:bookedPriorStart,
                        p.booked_prior_end_month=:bookedPriorEnd,
                        p.booked_plus_draft_available=:draftAvailable,
                        p.booked_plus_draft_reason=:draftReason,
                        p.booked_plus_draft_anchor_month=:draftAnchor,
                        p.booked_plus_draft_current_start_month=:draftCurrentStart,
                        p.booked_plus_draft_current_end_month=:draftCurrentEnd,
                        p.booked_plus_draft_prior_start_month=:draftPriorStart,
                        p.booked_plus_draft_prior_end_month=:draftPriorEnd,
                        p.publication_version = p.publication_version + 1
                    WHERE p.publication_id = 1
                      AND p.latest_cost_basis_request_id = :requestId
                      AND p.latest_cost_basis_request_vector = :vector
                      AND p.publication_version = :publicationVersion
                      AND b.full_refresh_version=:fullRefreshVersion
                      AND b.incremental_refresh_version=:incrementalRefreshVersion
                      AND pb.source_version=:practiceBasisInputVersion
                      AND gl.source_version=:financeGlVersion
                      AND ac.source_version=:accountClassificationVersion
                      AND c.refresh_enabled = TRUE AND c.control_version = :controlVersion
                      AND c.revenue_recovery_owner_token IS NULL
                    """);
            bindWindowParameters(publishQuery, "booked", bookedWindow);
            bindWindowParameters(publishQuery, "draft", bookedPlusDraftWindow);
            int published = publishQuery
                    .setParameter("generation", generationAt)
                    .setParameter("opexRows", candidate.costRowCount())
                    .setParameter("fteRows", candidate.fteRowCount())
                    .setParameter("completenessRows", candidate.completenessRowCount())
                    .setParameter("basis", basisGenerationId)
                    .setParameter("requestId", claim.requestId()).setParameter("vector", claim.inputVector())
                    .setParameter("fingerprint", contentFingerprint)
                    .setParameter("publicationVersion", integer(publication[5]))
                    .setParameter("fullRefreshVersion", claim.fullRefreshVersion())
                    .setParameter("incrementalRefreshVersion", claim.incrementalRefreshVersion())
                    .setParameter("practiceBasisInputVersion", claim.practiceBasisInputVersion())
                    .setParameter("financeGlVersion", claim.financeGlVersion())
                    .setParameter("accountClassificationVersion", claim.accountClassificationVersion())
                    .setParameter("controlVersion", claim.controlVersion()).executeUpdate();
            if (published != 1) throw new PublicationConflictException("COST_PUBLICATION_CAS_FAILED");
            int basisReady = em.createNativeQuery("""
                    UPDATE practice_basis_generation SET status = 'READY', published_at = UTC_TIMESTAMP(6)
                    WHERE generation_id = :generation AND status = 'RUNNING'
                    """).setParameter("generation", basisGenerationId).executeUpdate();
            if (basisReady != 1) throw new PublicationConflictException("COST_BASIS_CERTIFICATION_FAILED");
            if (terminalRequestUpdate(claim, "READY", generationAt, null, basisGenerationId,
                    contentFingerprint, null, basis.dependencyManifestFingerprint()) != 1) {
                throw new PublicationConflictException("REQUEST_OWNER_LOST");
            }
            pruneUnreferencedBasisGenerations();
            return new Outcome(claim.requestId(), "READY", generationAt, basisGenerationId, contentFingerprint);
        } finally {
            releaseLock(LOCK_NAME);
        }
    }

    private List<PracticeBasisMaterializationService.UserBasisInput> loadBasisUsers(LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT f.useruuid, f.companyuuid, f.document_date, f.gross_available_hours,
                       f.consultant_type, u.practice,
                       h.effective_from, h.effective_to, h.practice, h.source
                FROM fact_user_day f
                JOIN `user` u ON u.uuid = f.useruuid
                LEFT JOIN user_practice_history h ON h.useruuid = f.useruuid
                WHERE f.document_date BETWEEN :from AND :to
                ORDER BY f.useruuid, f.document_date, h.effective_from
                """).setParameter("from", from).setParameter("to", to).getResultList();
        Map<String, MutableUser> users = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String userUuid = text(row[0]);
            MutableUser user = users.computeIfAbsent(userUuid,
                    ignored -> new MutableUser(userUuid, text(row[4]), text(row[5])));
            user.capacities.putIfAbsent(toDate(row[2]), new PracticeBasisMaterializationService.CapacityInput(
                    toDate(row[2]), text(row[1]), decimal(row[3]), "FACT_USER_DAY", hash(values(row))));
            if (row[6] != null) {
                user.history.putIfAbsent(toDate(row[6]), new EffectivePracticeDateResolver.HistoryInterval(
                        toDate(row[6]), toDateOrNull(row[7]), text(row[8]), text(row[9])));
            }
        }
        return users.values().stream().map(user -> new PracticeBasisMaterializationService.UserBasisInput(
                user.userUuid, user.consultantType == null ? "UNAVAILABLE" : user.consultantType,
                user.currentPractice, List.copyOf(user.history.values()), List.copyOf(user.capacities.values())))
                .toList();
    }

    private CostCandidatePersistence buildAndPersistCostCandidate(
            String generationId, LocalDate coverageStart, LocalDate coverageEnd) {
        String fromKey = YearMonth.from(coverageStart).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        String toKey = YearMonth.from(coverageEnd).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));

        @SuppressWarnings("unchecked")
        List<Object[]> salaryRows = em.createNativeQuery("""
                SELECT useruuid, companyuuid, month_key, salary_sum
                FROM fact_salary_monthly
                WHERE month_key BETWEEN :fromKey AND :toKey AND salary_sum >= 0
                ORDER BY companyuuid, month_key, useruuid
                """).setParameter("fromKey", fromKey).setParameter("toKey", toKey).getResultList();
        List<PracticeCostCandidateBuilder.SalaryCell> salaries = salaryRows.stream()
                .map(row -> new PracticeCostCandidateBuilder.SalaryCell(
                        text(row[0]), text(row[1]), text(row[2]), decimal(row[3])))
                .toList();

        @SuppressWarnings("unchecked")
        List<Object[]> capacityRows = em.createNativeQuery("""
                SELECT user_uuid, company_uuid, capacity_date, practice_code,
                       consultant_type, gross_available_hours
                FROM practice_user_daily_capacity_basis_mat
                WHERE generation_id=:generation
                ORDER BY company_uuid, capacity_date, user_uuid, practice_code
                """).setParameter("generation", generationId).getResultList();
        List<PracticeCostCandidateBuilder.CapacityCell> capacities = capacityRows.stream()
                .map(row -> new PracticeCostCandidateBuilder.CapacityCell(
                        text(row[0]), text(row[1]), toDate(row[2]), text(row[3]), text(row[4]), decimal(row[5])))
                .toList();

        @SuppressWarnings("unchecked")
        List<Object[]> effectiveRows = em.createNativeQuery("""
                SELECT user_uuid, effective_from_date, effective_to_date_exclusive, practice_code
                FROM practice_user_effective_basis_mat
                WHERE generation_id=:generation
                ORDER BY user_uuid, effective_from_date
                """).setParameter("generation", generationId).getResultList();
        List<PracticeCostCandidateBuilder.EffectiveCell> effective = effectiveRows.stream()
                .map(row -> new PracticeCostCandidateBuilder.EffectiveCell(
                        text(row[0]), toDate(row[1]), toDate(row[2]), text(row[3])))
                .toList();

        @SuppressWarnings("unchecked")
        List<Object[]> controlRows = em.createNativeQuery("""
                SELECT fd.companyuuid, DATE_FORMAT(fd.expensedate, '%Y%m'),
                       COALESCE(fd.postingstatus, 'BOOKED'), aa.cost_type,
                       fd.accountnumber, SUM(fd.amount)
                FROM finance_details fd
                JOIN accounting_accounts aa
                  ON aa.companyuuid=fd.companyuuid AND aa.account_code=fd.accountnumber
                WHERE fd.expensedate >= :from AND fd.expensedate < :toExclusive
                  AND COALESCE(fd.postingstatus, 'BOOKED') IN ('BOOKED','DRAFT')
                  AND aa.cost_type IN ('SALARIES','OPEX')
                GROUP BY fd.companyuuid, DATE_FORMAT(fd.expensedate, '%Y%m'),
                         COALESCE(fd.postingstatus, 'BOOKED'), aa.cost_type, fd.accountnumber
                HAVING SUM(fd.amount) <> 0
                ORDER BY fd.companyuuid, DATE_FORMAT(fd.expensedate, '%Y%m'),
                         COALESCE(fd.postingstatus, 'BOOKED'), aa.cost_type, fd.accountnumber
                """).setParameter("from", coverageStart)
                .setParameter("toExclusive", coverageEnd.plusDays(1)).getResultList();
        List<PracticeCostCandidateBuilder.GlControl> controls = controlRows.stream().map(row -> {
            String stableKey = hash(text(row[0]), text(row[1]), text(row[2]), text(row[3]), text(row[4]));
            return new PracticeCostCandidateBuilder.GlControl(
                    stableKey, text(row[0]), text(row[1]), text(row[2]), text(row[3]), decimal(row[5]));
        }).toList();

        var candidate = costCandidateBuilder.build(salaries, capacities, effective, controls);
        String candidateFingerprint = hash(candidate.costs().toString(), candidate.ftes().toString(),
                candidate.salaryCoverage().toString(), candidate.unallocatedWithinTolerance().toString());
        if (!candidate.unallocatedWithinTolerance().isEmpty()) {
            log.warnf("practice cost controls unallocatable within reconciliation tolerance: %s",
                    candidate.unallocatedWithinTolerance());
        }

        for (var row : candidate.costs()) {
            em.createNativeQuery("""
                    INSERT INTO fact_practice_cost_generation_mat (
                        generation_id, company_id, practice_code, month_key, posting_status,
                        cost_type, source_control_key, allocated_amount_dkk, source_control_dkk,
                        content_fingerprint)
                    VALUES (:generation,:company,:practice,:month,:status,:type,:source,:amount,:control,:fingerprint)
                    """).setParameter("generation", generationId).setParameter("company", row.companyUuid())
                    .setParameter("practice", row.practiceCode()).setParameter("month", row.monthKey())
                    .setParameter("status", row.postingStatus()).setParameter("type", row.costType())
                    .setParameter("source", row.sourceControlKey()).setParameter("amount", row.amountDkk())
                    .setParameter("control", row.sourceControlDkk())
                    .setParameter("fingerprint", candidateFingerprint).executeUpdate();
        }
        for (var row : candidate.ftes()) {
            em.createNativeQuery("""
                    INSERT INTO fact_practice_fte_generation_mat (
                        generation_id, company_id, practice_code, month_key, billable_fte, content_fingerprint)
                    VALUES (:generation,:company,:practice,:month,:fte,:fingerprint)
                    """).setParameter("generation", generationId).setParameter("company", row.companyUuid())
                    .setParameter("practice", row.practiceCode()).setParameter("month", row.monthKey())
                    .setParameter("fte", row.billableFte()).setParameter("fingerprint", candidateFingerprint)
                    .executeUpdate();
        }
        int completenessRows = persistCompleteness(generationId, candidate, controls, candidateFingerprint);
        em.flush();

        Object[] certification = (Object[]) em.createNativeQuery("""
                SELECT (SELECT COUNT(*) FROM fact_practice_cost_generation_mat WHERE generation_id=:generation),
                       (SELECT COUNT(*) FROM fact_practice_fte_generation_mat WHERE generation_id=:generation),
                       (SELECT COUNT(*) FROM fact_practice_cost_completeness_generation_mat WHERE generation_id=:generation),
                       (SELECT COUNT(DISTINCT content_fingerprint) FROM fact_practice_cost_generation_mat
                         WHERE generation_id=:generation),
                       (SELECT MIN(content_fingerprint) FROM fact_practice_cost_generation_mat
                         WHERE generation_id=:generation),
                       (SELECT COUNT(DISTINCT content_fingerprint) FROM fact_practice_fte_generation_mat
                         WHERE generation_id=:generation),
                       (SELECT MIN(content_fingerprint) FROM fact_practice_fte_generation_mat
                         WHERE generation_id=:generation),
                       (SELECT COUNT(DISTINCT content_fingerprint)
                          FROM fact_practice_cost_completeness_generation_mat
                         WHERE generation_id=:generation),
                       (SELECT MIN(content_fingerprint)
                          FROM fact_practice_cost_completeness_generation_mat
                         WHERE generation_id=:generation),
                       (SELECT COUNT(*) FROM (
                            SELECT source_control_key
                              FROM fact_practice_cost_generation_mat
                             WHERE generation_id=:generation
                             GROUP BY source_control_key
                            HAVING SUM(allocated_amount_dkk) <> MAX(source_control_dkk)
                        ) non_conserving_controls)
                """).setParameter("generation", generationId).getSingleResult();
        if (number(certification[0]).intValue() != candidate.costs().size()
                || number(certification[1]).intValue() != candidate.ftes().size()
                || number(certification[2]).intValue() != completenessRows
                || number(certification[3]).intValue() != 1
                || !candidateFingerprint.equals(text(certification[4]))
                || number(certification[5]).intValue() != 1
                || !candidateFingerprint.equals(text(certification[6]))
                || number(certification[7]).intValue() != 1
                || !candidateFingerprint.equals(text(certification[8]))
                || number(certification[9]).intValue() != 0) {
            throw new PublicationConflictException("COST_CANDIDATE_CERTIFICATION_FAILED");
        }
        return new CostCandidatePersistence(candidate.costs().size(), candidate.ftes().size(),
                completenessRows, candidateFingerprint);
    }

    private int persistCompleteness(String generationId, PracticeCostCandidateBuilder.Result candidate,
                                    List<PracticeCostCandidateBuilder.GlControl> controls,
                                    String fingerprint) {
        int persisted = 0;
        for (var coverage : candidate.salaryCoverage()) {
            for (String source : List.of("BOOKED", "BOOKED_PLUS_DRAFT")) {
                BigDecimal signed = controls.stream()
                        .filter(row -> row.companyUuid().equals(coverage.companyUuid())
                                && row.monthKey().equals(coverage.monthKey())
                                && "SALARIES".equals(row.costType())
                                && ("BOOKED".equals(row.postingStatus())
                                || "BOOKED_PLUS_DRAFT".equals(source)))
                        .map(PracticeCostCandidateBuilder.GlControl::signedAmountDkk)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, java.math.RoundingMode.HALF_UP);
                List<PracticeCostCandidateBuilder.MonthlyCost> salaryCosts = candidate.costs().stream()
                        .filter(row -> row.companyUuid().equals(coverage.companyUuid())
                                && row.monthKey().equals(coverage.monthKey())
                                && "SALARIES".equals(row.costType())
                                && ("BOOKED".equals(row.postingStatus())
                                || "BOOKED_PLUS_DRAFT".equals(source))).toList();
                BigDecimal allocated = salaryCosts.stream().map(PracticeCostCandidateBuilder.MonthlyCost::amountDkk)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
                long expected = coverage.expectedPractices().stream()
                        .filter(PracticeCostSnapshotLoader.PRACTICES::contains).count();
                long actual = salaryCosts.stream().map(PracticeCostCandidateBuilder.MonthlyCost::practiceCode)
                        .filter(PracticeCostSnapshotLoader.PRACTICES::contains).distinct().count();
                long covered = coverage.expectedPractices().stream()
                        .filter(PracticeCostSnapshotLoader.PRACTICES::contains)
                        .filter(expectedPractice -> salaryCosts.stream()
                                .anyMatch(row -> row.practiceCode().equals(expectedPractice))).count();
                long missing = expected - covered;
                long unexpected = actual - covered;
                BigDecimal gap = allocated.subtract(signed).abs();
                BigDecimal allowed = signed.abs().multiply(new BigDecimal("0.0001"))
                        .max(BigDecimal.ONE).setScale(2, java.math.RoundingMode.HALF_UP);
                boolean complete = coverage.intendedSalaryDkk().signum() > 0
                        && signed.abs().multiply(BigDecimal.valueOf(100))
                                .compareTo(coverage.intendedSalaryDkk().multiply(BigDecimal.valueOf(85))) >= 0
                        && signed.abs().multiply(BigDecimal.valueOf(100))
                                .compareTo(coverage.intendedSalaryDkk().multiply(BigDecimal.valueOf(125))) <= 0
                        && missing == 0 && unexpected == 0 && gap.compareTo(allowed) <= 0;
                em.createNativeQuery("""
                        INSERT INTO fact_practice_cost_completeness_generation_mat (
                            generation_id, company_id, month_key, cost_source, intended_salary_dkk,
                            signed_salary_gl_dkk, allocated_salary_dkk, expected_salary_cell_count,
                            actual_salary_cell_count, covered_salary_cell_count, missing_salary_cell_count,
                            unexpected_salary_cell_count, allocation_gap_dkk, allowed_allocation_gap_dkk,
                            complete, cost_month_end_practice_fallback_employee_month_count,
                            content_fingerprint)
                        VALUES (:generation,:company,:month,:source,:intended,:signed,:allocated,:expected,
                                :actual,:covered,:missing,:unexpected,:gap,:allowed,:complete,:fallbacks,:fingerprint)
                        """).setParameter("generation", generationId)
                        .setParameter("company", coverage.companyUuid()).setParameter("month", coverage.monthKey())
                        .setParameter("source", source).setParameter("intended", coverage.intendedSalaryDkk().setScale(2, java.math.RoundingMode.HALF_UP))
                        .setParameter("signed", signed).setParameter("allocated", allocated)
                        .setParameter("expected", expected).setParameter("actual", actual)
                        .setParameter("covered", covered).setParameter("missing", missing)
                        .setParameter("unexpected", unexpected).setParameter("gap", gap)
                        .setParameter("allowed", allowed).setParameter("complete", complete)
                        .setParameter("fallbacks", coverage.costMonthEndPracticeFallbackEmployeeMonthCount())
                        .setParameter("fingerprint", fingerprint).executeUpdate();
                persisted++;
            }
        }
        return persisted;
    }

    private void verifyClaimVector(Claim claim) {
        Object[] live = (Object[]) em.createNativeQuery("""
                SELECT b.full_refresh_version, b.incremental_refresh_version,
                       pb.source_version, gl.source_version, ac.source_version,
                       c.refresh_enabled, c.control_version, c.revenue_recovery_owner_token,
                       r.status, r.owner_token, r.input_vector_fingerprint
                FROM bi_refresh_watermark b
                JOIN practice_revenue_source_watermark pb ON pb.source_name='PRACTICE_BASIS_INPUT'
                JOIN practice_revenue_source_watermark gl ON gl.source_name='FINANCE_GL'
                JOIN practice_revenue_source_watermark ac ON ac.source_name='ACCOUNT_CLASSIFICATION'
                JOIN practice_contribution_publication_control c ON c.control_id=1
                JOIN practice_cost_basis_refresh_request r ON r.request_id=:requestId
                WHERE b.pipeline_name='FACT_USER_DAY'
                """).setParameter("requestId", claim.requestId()).getSingleResult();
        boolean coherent = claim.fullRefreshVersion().equals(integer(live[0]))
                && claim.incrementalRefreshVersion().equals(integer(live[1]))
                && claim.practiceBasisInputVersion().equals(integer(live[2]))
                && claim.financeGlVersion().equals(integer(live[3]))
                && claim.accountClassificationVersion().equals(integer(live[4]))
                && bool(live[5]) && claim.controlVersion().equals(integer(live[6])) && live[7] == null
                && "RUNNING".equals(text(live[8])) && claim.ownerToken().equals(text(live[9]))
                && claim.inputVector().equals(text(live[10]));
        if (!coherent) throw new PublicationConflictException("COST_SOURCE_VECTOR_ADVANCED");
    }

    private void verifyFinalControl(Claim claim, Object[] publication) {
        verifyClaimVector(claim);
        if (!claim.requestId().equals(integer(publication[0]))
                || !claim.inputVector().equals(text(publication[1]))) {
            // The latest-request pointer moved to a newer covering input while this owner was building.
            // Retire as SUPERSEDED pointing at that successor (defect 9) rather than as a technical FAILED.
            throw new CostRequestSupersededException(successorRequestId(claim.requestId()));
        }
    }

    /** The newest request beyond {@code requestId} is the successor a displaced owner points at. */
    BigInteger successorRequestId(BigInteger requestId) {
        Object successor = em.createNativeQuery("""
                SELECT MAX(request_id) FROM practice_cost_basis_refresh_request WHERE request_id > :id
                """).setParameter("id", requestId).getSingleResult();
        return successor == null ? null : integer(successor);
    }

    /**
     * Retires a displaced owned RUNNING request as SUPERSEDED pointing at its successor and drops only
     * its unpublished candidate. A missing successor cannot happen for a genuine supersession, but is
     * defended against by falling back to a technical FAILED rather than an invalid self-link.
     */
    void supersedeAndCleanup(Claim claim, BigInteger successorRequestId) {
        deleteRunningBasisCandidates();
        if (successorRequestId == null || successorRequestId.equals(claim.requestId())) {
            failAndCleanup(claim, "COST_BASIS_SUPERSESSION_SUCCESSOR_MISSING");
            return;
        }
        em.createNativeQuery("""
                UPDATE practice_cost_basis_refresh_request
                SET status='SUPERSEDED', superseded_by_request_id=:successor, owner_token=NULL,
                    completed_at=UTC_TIMESTAMP(6), safe_reason='SUPERSEDED_BY_NEWER_INPUT',
                    optimistic_version=optimistic_version+1
                WHERE request_id=:id AND status='RUNNING' AND owner_token=:owner
                  AND :successor > request_id
                """).setParameter("successor", successorRequestId).setParameter("id", claim.requestId())
                .setParameter("owner", claim.ownerToken()).executeUpdate();
    }

    /**
     * Durably advances the monotonic dependency-manifest input version and enqueues the successor
     * DEPENDENCY_MANIFEST_INPUT request carrying the recomputed manifest fingerprint and affected bounds.
     * Idempotent for an identical still-PENDING miss (request_key dedupe inside the procedure).
     */
    BigInteger escalateDependencyManifestMiss(DependencyManifestMissException miss) {
        em.createNativeQuery("CALL sp_advance_practice_dependency_manifest_input(:start, :end, :fingerprint)")
                .setParameter("start", miss.affectedStart()).setParameter("end", miss.affectedEnd())
                .setParameter("fingerprint", miss.manifestFingerprint()).executeUpdate();
        return integer(em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult());
    }

    /**
     * A same-input technical failure is retried on the SAME row: FAILED -> PENDING with an incremented
     * attempt count, guarded by the optimistic version and by the row still being the latest request.
     * A dominated failure is never disguised as a retryable one and never creates a duplicate request.
     */
    @jakarta.transaction.Transactional
    public int retryTechnicalFailure(BigInteger requestId, long expectedVersion) {
        return em.createNativeQuery("""
                UPDATE practice_cost_basis_refresh_request r
                JOIN practice_operating_cost_publication p ON p.publication_id=1
                SET r.status='PENDING', r.owner_token=NULL, r.claimed_at=NULL, r.failed_at=NULL,
                    r.attempt_count=r.attempt_count+1, r.safe_reason='TECHNICAL_RETRY',
                    r.optimistic_version=r.optimistic_version+1
                WHERE r.request_id=:id AND r.status='FAILED' AND r.optimistic_version=:version
                  AND p.latest_cost_basis_request_id=r.request_id
                  AND NOT EXISTS (
                      SELECT 1 FROM practice_cost_basis_refresh_request newer
                      WHERE newer.request_id > r.request_id
                  )
                """).setParameter("id", requestId).setParameter("version", expectedVersion).executeUpdate();
    }

    private void deleteRunningBasisCandidates() {
        @SuppressWarnings("unchecked")
        List<String> candidates = em.createNativeQuery("""
                SELECT generation_id FROM practice_basis_generation
                WHERE status='RUNNING' AND generation_id NOT IN (
                    SELECT COALESCE(practice_basis_generation_id, '') FROM practice_operating_cost_publication)
                """).getResultList();
        candidates.forEach(this::deleteBasisCandidate);
    }

    private int terminalRequestUpdate(Claim claim, String status, LocalDateTime resulting,
                                      LocalDateTime compared, String basis, String fingerprint, String reason,
                                      String dependencyFingerprint) {
        // The certified manifest fingerprint is written atomically onto the terminal request row so the
        // activation predicates (req.dependency_fingerprint = basis.dependency_manifest_fingerprint) become
        // satisfiable for exactly the certified generation. Both READY and byte-equivalent NO_CHANGE carry it.
        return em.createNativeQuery("""
                UPDATE practice_cost_basis_refresh_request
                SET status=:status, owner_token=NULL, completed_at=UTC_TIMESTAMP(6),
                    resulting_cost_generation_at=:resulting,
                    resulting_basis_generation_id=CASE WHEN :status='READY' THEN :basis ELSE NULL END,
                    compared_cost_generation_at=:compared,
                    compared_basis_generation_id=CASE WHEN :status='NO_CHANGE' THEN :basis ELSE NULL END,
                    content_fingerprint=:fingerprint, dependency_fingerprint=:dependencyFingerprint,
                    safe_reason=:reason, optimistic_version=optimistic_version+1
                WHERE request_id=:id AND status='RUNNING' AND owner_token=:owner
                """).setParameter("status", status).setParameter("resulting", resulting)
                .setParameter("basis", basis).setParameter("compared", compared)
                .setParameter("fingerprint", fingerprint)
                .setParameter("dependencyFingerprint", dependencyFingerprint).setParameter("reason", reason)
                .setParameter("id", claim.requestId()).setParameter("owner", claim.ownerToken()).executeUpdate();
    }

    private static void bindWindowParameters(
            Query query, String prefix, PracticeCostSnapshotProvider.CanonicalWindow window) {
        query.setParameter(prefix + "Available", window.available());
        query.setParameter(prefix + "Reason", window.reason());
        query.setParameter(prefix + "Anchor", window.anchor());
        query.setParameter(prefix + "CurrentStart", window.currentStart());
        query.setParameter(prefix + "CurrentEnd", window.currentEnd());
        query.setParameter(prefix + "PriorStart", window.priorStart());
        query.setParameter(prefix + "PriorEnd", window.priorEnd());
    }

    void failAndCleanup(Claim claim, String safeReason) {
        @SuppressWarnings("unchecked")
        List<String> candidates = em.createNativeQuery("""
                SELECT generation_id FROM practice_basis_generation
                WHERE status='RUNNING' AND generation_id NOT IN (
                    SELECT COALESCE(practice_basis_generation_id, '') FROM practice_operating_cost_publication)
                """).getResultList();
        candidates.forEach(this::deleteBasisCandidate);
        em.createNativeQuery("""
                UPDATE practice_cost_basis_refresh_request
                SET status='FAILED', owner_token=NULL, failed_at=UTC_TIMESTAMP(6), safe_reason=:reason,
                    optimistic_version=optimistic_version+1
                WHERE request_id=:id AND status='RUNNING' AND owner_token=:owner
                """).setParameter("reason", safeReason).setParameter("id", claim.requestId())
                .setParameter("owner", claim.ownerToken()).executeUpdate();
    }

    private void deleteBasisCandidate(String generationId) {
        em.createNativeQuery("DELETE FROM practice_basis_generation WHERE generation_id=:id AND status <> 'READY'")
                .setParameter("id", generationId).executeUpdate();
    }

    private void pruneUnreferencedBasisGenerations() {
        em.createNativeQuery("""
                DELETE b FROM practice_basis_generation b
                WHERE b.status IN ('FAILED','READY')
                  AND b.generation_id NOT IN (
                    SELECT COALESCE(practice_basis_generation_id, '') FROM practice_operating_cost_publication
                    UNION SELECT COALESCE(practice_basis_generation_id, '') FROM practice_revenue_publication
                  )
                  AND b.created_at < DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 90 DAY)
                """).executeUpdate();
    }

    private boolean acquireLock(String name) {
        Object value = em.createNativeQuery("SELECT GET_LOCK(:name, :seconds)")
                .setParameter("name", name).setParameter("seconds", Math.toIntExact(lockWait.toSeconds()))
                .getSingleResult();
        return number(value).intValue() == 1;
    }
    private void releaseLock(String name) {
        try { em.createNativeQuery("SELECT RELEASE_LOCK(:name)").setParameter("name", name).getSingleResult(); }
        catch (RuntimeException releaseFailure) { log.warnf(releaseFailure, "could not release %s", name); }
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String values(Object[] values) { return java.util.Arrays.deepToString(values); }
    /**
     * Only a proven byte-equivalent INCREMENTAL_BI candidate may finish NO_CHANGE (design §10 point 4).
     * FULL_BI, PRACTICE_BASIS_INPUT, COST_GL_INPUT, and DEPENDENCY_MANIFEST_INPUT must always publish a new
     * certified generation even when the consolidated cost is byte-identical, because the older immutable
     * generation was never certified against the newer evidence/coverage.
     */
    static boolean shouldCertifyNoChange(String cause, String candidateFingerprint, String publishedFingerprint) {
        return "INCREMENTAL_BI".equals(cause) && candidateFingerprint != null
                && candidateFingerprint.equals(publishedFingerprint);
    }
    private static Number number(Object value) { return (Number) value; }
    private static BigInteger integer(Object value) { return value instanceof BigInteger i ? i : new BigInteger(value.toString()); }
    private static BigDecimal decimal(Object value) { return value instanceof BigDecimal d ? d : new BigDecimal(value.toString()); }
    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static boolean bool(Object value) { return value instanceof Boolean b ? b : number(value).intValue() != 0; }
    private static LocalDate toDate(Object value) { return value instanceof LocalDate d ? d : ((java.sql.Date) value).toLocalDate(); }
    private static LocalDate toDateOrNull(Object value) { return value == null ? null : toDate(value); }
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dt) return dt;
        return ((java.sql.Timestamp) value).toLocalDateTime();
    }

    private static final class MutableUser {
        final String userUuid; final String consultantType; final String currentPractice;
        final Map<LocalDate, EffectivePracticeDateResolver.HistoryInterval> history = new LinkedHashMap<>();
        final Map<LocalDate, PracticeBasisMaterializationService.CapacityInput> capacities = new LinkedHashMap<>();
        MutableUser(String userUuid, String consultantType, String currentPractice) {
            this.userUuid=userUuid; this.consultantType=consultantType; this.currentPractice=currentPractice;
        }
    }

    record Claim(BigInteger requestId, String requestKey, String cause, String inputVector,
                 BigInteger fullRefreshVersion, BigInteger incrementalRefreshVersion,
                 BigInteger practiceBasisInputVersion, BigInteger financeGlVersion,
                 BigInteger accountClassificationVersion, String capturedDependencyFingerprint,
                 BigInteger controlVersion, String ownerToken) {}
    private record CostCandidatePersistence(int costRowCount, int fteRowCount,
                                            int completenessRowCount, String contentFingerprint) {}
    public record Outcome(BigInteger requestId, String status, LocalDateTime costGenerationAt,
                          String basisGenerationId, String contentFingerprint) {
        static Outcome noPending() { return new Outcome(null, "NO_PENDING", null, null, null); }
    }
    public record ExpectedRequest(BigInteger requestId, String requestKey,
                                  String inputVectorFingerprint) {
        public static final String REQUEST_ID_PROPERTY = "expectedRequestId";
        public static final String REQUEST_KEY_PROPERTY = "expectedRequestKey";
        public static final String INPUT_VECTOR_PROPERTY = "expectedInputVectorFingerprint";

        public ExpectedRequest {
            if (requestId == null || requestId.signum() <= 0
                    || requestKey == null || !SHA_256.matcher(requestKey).matches()
                    || inputVectorFingerprint == null
                    || !SHA_256.matcher(inputVectorFingerprint).matches()) {
                throw new IllegalArgumentException("closed expected cost request identity is required");
            }
        }

        public static ExpectedRequest fromJobProperties(Properties properties) {
            if (properties == null) {
                throw new IllegalArgumentException("expected cost request job properties are required");
            }
            try {
                return new ExpectedRequest(
                        new BigInteger(properties.getProperty(REQUEST_ID_PROPERTY, "")),
                        properties.getProperty(REQUEST_KEY_PROPERTY),
                        properties.getProperty(INPUT_VECTOR_PROPERTY));
            } catch (NumberFormatException invalidId) {
                throw new IllegalArgumentException("expected cost request id must be a positive integer", invalidId);
            }
        }

        public Properties toJobProperties() {
            Properties properties = new Properties();
            properties.setProperty(REQUEST_ID_PROPERTY, requestId.toString());
            properties.setProperty(REQUEST_KEY_PROPERTY, requestKey);
            properties.setProperty(INPUT_VECTOR_PROPERTY, inputVectorFingerprint);
            return properties;
        }
    }
    public static class PublicationConflictException extends IllegalStateException {
        public PublicationConflictException(String message) { super(message); }
    }

    /** A displaced owned request that must retire as SUPERSEDED pointing at the successor, not FAILED. */
    public static class CostRequestSupersededException extends PublicationConflictException {
        private final transient BigInteger successorRequestId;
        public CostRequestSupersededException(BigInteger successorRequestId) {
            super("COST_REQUEST_SUPERSEDED");
            this.successorRequestId = successorRequestId;
        }
        public BigInteger successorRequestId() { return successorRequestId; }
    }

    /** A manifest/coverage miss that must fail closed and escalate DEPENDENCY_MANIFEST_INPUT. */
    public static class DependencyManifestMissException extends PublicationConflictException {
        private final transient LocalDate affectedStart;
        private final transient LocalDate affectedEnd;
        private final transient String manifestFingerprint;
        public DependencyManifestMissException(LocalDate affectedStart, LocalDate affectedEnd,
                                               String manifestFingerprint) {
            super("BASIS_COVERAGE_MISS");
            this.affectedStart = affectedStart;
            this.affectedEnd = affectedEnd;
            this.manifestFingerprint = manifestFingerprint;
        }
        public LocalDate affectedStart() { return affectedStart; }
        public LocalDate affectedEnd() { return affectedEnd; }
        public String manifestFingerprint() { return manifestFingerprint; }
    }
}
