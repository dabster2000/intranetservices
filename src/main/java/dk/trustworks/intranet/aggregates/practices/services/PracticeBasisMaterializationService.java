package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.model.PracticeBasisDependencyManifest;
import dk.trustworks.intranet.aggregates.practices.model.PracticeBasisGeneration;
import dk.trustworks.intranet.aggregates.practices.model.PracticeUserDailyCapacityBasis;
import dk.trustworks.intranet.aggregates.practices.model.PracticeUserEffectiveBasis;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Persists one immutable effective-practice and daily-capacity generation. */
@ApplicationScoped
public class PracticeBasisMaterializationService {
    public static final String FALLBACK_POLICY_VERSION = "CURRENT_PRACTICE_PRE_HISTORY_V1";
    public static final String CONSULTANT_TYPE_POLICY_VERSION = "USER_STATUS_EFFECTIVE_V1";

    @Inject EntityManager em;
    @Inject EffectivePracticeDateResolver resolver;

    @Transactional
    public Result materialize(BuildInput input) {
        PreparedBasis prepared = prepare(input);
        LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

        PracticeBasisGeneration generation = new PracticeBasisGeneration();
        generation.generationId = prepared.generationId();
        generation.status = "RUNNING";
        generation.coverageStartDate = input.manifest().coverageStart();
        generation.coverageEndDate = input.manifest().coverageEnd();
        generation.historyCoverageStartDate = prepared.historyCoverageStart();
        generation.fallbackPolicyVersion = FALLBACK_POLICY_VERSION;
        generation.consultantTypePolicyVersion = CONSULTANT_TYPE_POLICY_VERSION;
        generation.fullRefreshVersion = input.fullRefreshVersion();
        generation.incrementalRefreshVersion = input.incrementalRefreshVersion();
        generation.practiceBasisInputSourceVersion = input.practiceBasisInputSourceVersion();
        generation.sourceFingerprint = prepared.sourceFingerprint();
        generation.capacitySourceFingerprint = prepared.capacityFingerprint();
        generation.dependencyManifestFingerprint = input.manifest().fingerprint();
        generation.createdAt = createdAt;
        em.persist(generation);

        for (EffectiveRow row : prepared.effectiveRows()) {
            PracticeUserEffectiveBasis entity = new PracticeUserEffectiveBasis();
            entity.generationId = prepared.generationId();
            entity.userUuid = row.userUuid();
            entity.effectiveFromDate = row.effectiveFrom();
            entity.effectiveToDateExclusive = row.effectiveToExclusive();
            entity.consultantType = row.consultantType();
            entity.practiceCode = row.practiceCode();
            entity.attributionBasis = row.attributionBasis();
            entity.fallbackReason = row.fallbackReason();
            entity.sourceEvidence = row.sourceEvidence();
            entity.sourceFingerprint = row.sourceFingerprint();
            entity.createdAt = createdAt;
            em.persist(entity);
        }
        for (CapacityRow row : prepared.capacityRows()) {
            PracticeUserDailyCapacityBasis entity = new PracticeUserDailyCapacityBasis();
            entity.generationId = prepared.generationId();
            entity.userUuid = row.userUuid();
            entity.capacityDate = row.date();
            entity.companyUuid = row.companyUuid();
            entity.grossAvailableHours = row.grossAvailableHours();
            entity.effectiveBasisFromDate = row.effectiveBasisFrom();
            entity.consultantType = row.consultantType();
            entity.practiceCode = row.practiceCode();
            entity.capacitySource = row.capacitySource();
            entity.capacitySourceFingerprint = row.capacitySourceFingerprint();
            entity.historicalPracticeFallback = row.historicalFallback();
            entity.createdAt = createdAt;
            em.persist(entity);
        }
        int sequence = 0;
        for (var row : input.manifest().dependencies()) {
            PracticeBasisDependencyManifest entity = new PracticeBasisDependencyManifest();
            entity.generationId = prepared.generationId();
            entity.manifestSequence = ++sequence;
            entity.recognizedDocumentUuid = row.recognizedDocumentUuid();
            entity.recognizedItemUuid = row.recognizedItemUuid();
            entity.recognizedDocumentType = row.recognizedDocumentType();
            entity.recognizedMonth = row.recognizedMonth();
            entity.dependencyKind = row.dependencyKind();
            entity.sourceDocumentUuid = row.sourceDocumentUuid();
            entity.sourceItemUuid = row.sourceItemUuid();
            entity.requiredStartDate = row.requiredStartDate();
            entity.requiredEndDate = row.requiredEndDate();
            entity.sourceFingerprint = row.sourceFingerprint();
            entity.createdAt = createdAt;
            em.persist(entity);
        }
        em.flush();
        return new Result(prepared.generationId(), prepared.sourceFingerprint(),
                prepared.capacityFingerprint(), input.manifest().fingerprint(),
                prepared.effectiveRows().size(), prepared.capacityRows().size(), sequence);
    }

    PreparedBasis prepare(BuildInput input) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(input.manifest(), "manifest");
        String generationId = input.generationId() == null ? UUID.randomUUID().toString() : input.generationId();
        Map<String, EffectiveRow> effectiveRows = new LinkedHashMap<>();
        List<CapacityRow> capacityRows = new ArrayList<>();
        LocalDate historyCoverageStart = null;

        List<UserBasisInput> users = input.users().stream()
                .sorted(Comparator.comparing(UserBasisInput::userUuid)).toList();
        for (UserBasisInput user : users) {
            List<EffectivePracticeDateResolver.HistoryInterval> history = resolver.validated(user.history());
            if (!history.isEmpty() && (historyCoverageStart == null
                    || history.getFirst().effectiveFrom().isBefore(historyCoverageStart))) {
                historyCoverageStart = history.getFirst().effectiveFrom();
            }
            LocalDate fallbackEnd = history.isEmpty()
                    ? input.manifest().coverageEnd().plusDays(1) : history.getFirst().effectiveFrom();
            if (input.manifest().coverageStart().isBefore(fallbackEnd)) {
                if (user.currentPractice() == null || user.currentPractice().isBlank()) {
                    throw new BasisMaterializationException("CURRENT_PRACTICE_UNAVAILABLE");
                }
                addEffective(effectiveRows, new EffectiveRow(user.userUuid(), input.manifest().coverageStart(),
                        fallbackEnd, user.consultantType(), user.currentPractice(),
                        "CURRENT_PRACTICE_FALLBACK", "BEFORE_HISTORY_COVERAGE", "USER_CURRENT",
                        hash(user.userUuid(), user.currentPractice(), input.manifest().coverageStart().toString())));
            }
            for (var interval : history) {
                LocalDate from = interval.effectiveFrom().isBefore(input.manifest().coverageStart())
                        ? input.manifest().coverageStart() : interval.effectiveFrom();
                LocalDate to = interval.effectiveToExclusive() == null
                        || interval.effectiveToExclusive().isAfter(input.manifest().coverageEnd().plusDays(1))
                        ? input.manifest().coverageEnd().plusDays(1) : interval.effectiveToExclusive();
                if (from.isBefore(to)) {
                    addEffective(effectiveRows, new EffectiveRow(user.userUuid(), from, to,
                            user.consultantType(), interval.practice(), "HISTORY", null,
                            interval.sourceEvidence() == null ? "USER_PRACTICE_HISTORY" : interval.sourceEvidence(),
                            hash(user.userUuid(), interval.practice(), from.toString(), to.toString())));
                }
            }

            for (CapacityInput capacity : user.capacities().stream()
                    .sorted(Comparator.comparing(CapacityInput::date)).toList()) {
                if (capacity.date().isBefore(input.manifest().coverageStart())
                        || capacity.date().isAfter(input.manifest().coverageEnd())) continue;
                if (capacity.grossAvailableHours() == null || capacity.grossAvailableHours().signum() < 0) {
                    throw new BasisMaterializationException("INVALID_GROSS_CAPACITY");
                }
                var resolution = resolver.resolve(capacity.date(), history, user.currentPractice());
                if (!resolution.available()) throw new BasisMaterializationException(resolution.reason().name());
                EffectiveRow basis = effectiveRows.values().stream()
                        .filter(row -> row.userUuid().equals(user.userUuid()))
                        .filter(row -> !capacity.date().isBefore(row.effectiveFrom())
                                && capacity.date().isBefore(row.effectiveToExclusive()))
                        .findFirst().orElseThrow(() -> new BasisMaterializationException("BASIS_COVERAGE_MISS"));
                BigDecimal hours = capacity.grossAvailableHours().setScale(6, java.math.RoundingMode.HALF_UP);
                capacityRows.add(new CapacityRow(user.userUuid(), capacity.date(), capacity.companyUuid(), hours,
                        basis.effectiveFrom(), user.consultantType(), resolution.practice(),
                        capacity.source(), hash(user.userUuid(), capacity.date().toString(), hours.toPlainString(),
                        String.valueOf(capacity.sourceFingerprint())),
                        resolution.status() == EffectivePracticeDateResolver.Status.CURRENT_PRACTICE_FALLBACK));
            }
        }
        capacityRows.sort(Comparator.comparing(CapacityRow::userUuid).thenComparing(CapacityRow::date));
        List<EffectiveRow> rows = effectiveRows.values().stream()
                .sorted(Comparator.comparing(EffectiveRow::userUuid).thenComparing(EffectiveRow::effectiveFrom))
                .toList();
        return new PreparedBasis(generationId, rows, List.copyOf(capacityRows), historyCoverageStart,
                hash(rows.toString(), input.manifest().fingerprint()),
                hash(capacityRows.toString(), input.manifest().fingerprint()));
    }

    private static void addEffective(Map<String, EffectiveRow> target, EffectiveRow row) {
        target.put(row.userUuid() + '|' + row.effectiveFrom(), row);
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record BuildInput(String generationId, PracticeRevenueDependencyManifestProvider.Manifest manifest,
                             BigInteger fullRefreshVersion, BigInteger incrementalRefreshVersion,
                             BigInteger practiceBasisInputSourceVersion, List<UserBasisInput> users) {
        public BuildInput { users = List.copyOf(users); }
    }
    public record UserBasisInput(String userUuid, String consultantType, String currentPractice,
                                 List<EffectivePracticeDateResolver.HistoryInterval> history,
                                 List<CapacityInput> capacities) {
        public UserBasisInput { history = List.copyOf(history); capacities = List.copyOf(capacities); }
    }
    public record CapacityInput(LocalDate date, String companyUuid, BigDecimal grossAvailableHours,
                                String source, String sourceFingerprint) {}
    record PreparedBasis(String generationId, List<EffectiveRow> effectiveRows, List<CapacityRow> capacityRows,
                         LocalDate historyCoverageStart, String sourceFingerprint, String capacityFingerprint) {}
    record EffectiveRow(String userUuid, LocalDate effectiveFrom, LocalDate effectiveToExclusive,
                        String consultantType, String practiceCode, String attributionBasis,
                        String fallbackReason, String sourceEvidence, String sourceFingerprint) {}
    record CapacityRow(String userUuid, LocalDate date, String companyUuid, BigDecimal grossAvailableHours,
                       LocalDate effectiveBasisFrom, String consultantType, String practiceCode,
                       String capacitySource, String capacitySourceFingerprint, boolean historicalFallback) {}
    public record Result(String generationId, String sourceFingerprint, String capacityFingerprint,
                         String dependencyManifestFingerprint, int effectiveRowCount,
                         int capacityRowCount, int dependencyRowCount) {}

    public static class BasisMaterializationException extends IllegalStateException {
        public BasisMaterializationException(String message) { super(message); }
    }
}
