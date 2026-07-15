package dk.trustworks.intranet.aggregates.invoice.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves registered delivery from the non-coalesced work/rate join. The resolver owns
 * canonical identity and evidence only; callers decide whether zero-rate rows are eligible.
 */
@ApplicationScoped
public class RegisteredDeliveryEvidenceResolver {

    static final int SOURCE_SCALE = 6;
    static final int PRODUCT_SCALE = 12;

    @Inject
    EntityManager em;

    public List<ResolvedDelivery> resolve(QueryInput input) {
        Objects.requireNonNull(input, "input");
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT w.uuid,
                       w.useruuid,
                       COALESCE(NULLIF(TRIM(w.workas), ''), w.useruuid) AS effective_consultant_uuid,
                       w.registered,
                       w.taskuuid,
                       p.uuid AS projectuuid,
                       c.uuid AS contractuuid,
                       cp.uuid AS contract_project_uuid,
                       cc.uuid AS contract_consultant_uuid,
                       CAST(w.workduration AS DECIMAL(24,6)) AS workduration,
                       CAST(cc.rate AS DECIMAL(24,6)) AS resolved_rate
                FROM work w
                JOIN task t ON t.uuid = w.taskuuid
                JOIN project p ON p.uuid = t.projectuuid
                JOIN contract_project cp ON cp.projectuuid = p.uuid
                JOIN contracts c ON c.uuid = cp.contractuuid
                LEFT JOIN contract_consultants cc
                  ON cc.contractuuid = c.uuid
                 AND cc.useruuid = COALESCE(NULLIF(TRIM(w.workas), ''), w.useruuid)
                 AND cc.activefrom <= w.registered
                 AND cc.activeto >= w.registered
                WHERE c.uuid = :contractUuid
                  AND p.uuid = :projectUuid
                  AND w.registered >= :startDate
                  AND w.registered < :endDateExclusive
                ORDER BY w.uuid, effective_consultant_uuid, cp.uuid, cc.uuid, resolved_rate
                """)
                .setParameter("contractUuid", input.contractUuid())
                .setParameter("projectUuid", input.projectUuid())
                .setParameter("startDate", input.startDate())
                .setParameter("endDateExclusive", input.endDateExclusive())
                .getResultList();
        List<RawDeliveryRow> raw = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            raw.add(new RawDeliveryRow(
                    text(row[0]), text(row[1]), text(row[2]), toDate(row[3]),
                    text(row[4]), text(row[5]), text(row[6]),
                    text(row[7]), text(row[8]), decimalText(row[9]), decimalText(row[10])));
        }
        return resolveRows(raw);
    }

    public List<ResolvedDelivery> resolveRows(List<RawDeliveryRow> sourceRows) {
        Map<String, List<RawDeliveryRow>> byWork = new LinkedHashMap<>();
        sourceRows.stream()
                .sorted(Comparator.comparing(RawDeliveryRow::workUuid)
                        .thenComparing(row -> nullSafe(row.effectiveConsultantUuid()))
                        .thenComparing(row -> nullSafe(row.contractProjectUuid()))
                        .thenComparing(row -> nullSafe(row.contractConsultantUuid()))
                        .thenComparing(row -> nullSafe(row.rateText())))
                .forEach(row -> byWork.computeIfAbsent(row.workUuid(), ignored -> new ArrayList<>()).add(row));

        List<ResolvedDelivery> result = new ArrayList<>(byWork.size());
        for (Map.Entry<String, List<RawDeliveryRow>> entry : byWork.entrySet()) {
            List<CanonicalCandidate> candidates = entry.getValue().stream()
                    .map(this::canonicalize)
                    .distinct()
                    .toList();
            if (candidates.stream().anyMatch(candidate -> candidate.status == RateResolutionStatus.INVALID)) {
                result.add(unavailable(entry.getValue().getFirst(), RateResolutionStatus.INVALID));
            } else if (candidates.size() != 1) {
                result.add(unavailable(entry.getValue().getFirst(), RateResolutionStatus.AMBIGUOUS));
            } else {
                CanonicalCandidate candidate = candidates.getFirst();
                if (candidate.rate == null) {
                    result.add(unavailable(entry.getValue().getFirst(), RateResolutionStatus.MISSING));
                } else {
                    result.add(new ResolvedDelivery(candidate.workUuid, candidate.registrantUuid,
                            candidate.effectiveConsultantUuid, candidate.deliveryDate, candidate.taskUuid,
                            candidate.projectUuid, candidate.contractUuid, candidate.contractProjectUuid,
                            candidate.contractConsultantUuid, candidate.duration, candidate.rate,
                            candidate.duration.multiply(candidate.rate).setScale(PRODUCT_SCALE, RoundingMode.UNNECESSARY),
                            RateResolutionStatus.RESOLVED));
                }
            }
        }
        return List.copyOf(result);
    }

    private CanonicalCandidate canonicalize(RawDeliveryRow row) {
        try {
            BigDecimal duration = normalize(row.durationText());
            BigDecimal rate = row.rateText() == null ? null : normalize(row.rateText());
            if (rate != null && rate.signum() < 0) {
                return CanonicalCandidate.invalid(row.workUuid());
            }
            return new CanonicalCandidate(row.workUuid(), row.registrantUuid(), row.effectiveConsultantUuid(),
                    row.deliveryDate(), row.taskUuid(), row.projectUuid(), row.contractUuid(), duration, rate,
                    row.contractProjectUuid(), row.contractConsultantUuid(), RateResolutionStatus.RESOLVED);
        } catch (RuntimeException e) {
            return CanonicalCandidate.invalid(row.workUuid());
        }
    }

    private static BigDecimal normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing decimal");
        }
        BigDecimal parsed = new BigDecimal(value);
        if (Math.max(0, parsed.precision() - parsed.scale()) > 18) {
            throw new IllegalArgumentException("decimal out of range");
        }
        return parsed.setScale(SOURCE_SCALE, RoundingMode.HALF_UP);
    }

    private static ResolvedDelivery unavailable(RawDeliveryRow row, RateResolutionStatus status) {
        BigDecimal duration;
        try {
            duration = normalize(row.durationText());
        } catch (RuntimeException ignored) {
            duration = null;
        }
        return new ResolvedDelivery(row.workUuid(), row.registrantUuid(), row.effectiveConsultantUuid(),
                row.deliveryDate(), row.taskUuid(), row.projectUuid(), row.contractUuid(),
                row.contractProjectUuid(), row.contractConsultantUuid(), duration, null, null, status);
    }

    private static String decimalText(Object value) {
        return value == null ? null : value.toString();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalDate toDate(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return value == null ? null : LocalDate.parse(value.toString());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public record QueryInput(String contractUuid, String projectUuid,
                             LocalDate startDate, LocalDate endDateExclusive) {
        public QueryInput {
            Objects.requireNonNull(contractUuid, "contractUuid");
            Objects.requireNonNull(projectUuid, "projectUuid");
            Objects.requireNonNull(startDate, "startDate");
            Objects.requireNonNull(endDateExclusive, "endDateExclusive");
            if (!endDateExclusive.isAfter(startDate)) {
                throw new IllegalArgumentException("endDateExclusive must be after startDate");
            }
        }
    }

    public record RawDeliveryRow(String workUuid, String registrantUuid, String effectiveConsultantUuid,
                                 LocalDate deliveryDate, String taskUuid, String projectUuid,
                                 String contractUuid, String contractProjectUuid,
                                 String contractConsultantUuid, String durationText, String rateText) {
        public RawDeliveryRow(String workUuid, String registrantUuid, String effectiveConsultantUuid,
                              LocalDate deliveryDate, String taskUuid, String projectUuid,
                              String contractUuid, String durationText, String rateText) {
            this(workUuid, registrantUuid, effectiveConsultantUuid, deliveryDate, taskUuid, projectUuid,
                    contractUuid, null, null, durationText, rateText);
        }
    }

    public record ResolvedDelivery(String workUuid, String registrantUuid, String effectiveConsultantUuid,
                                   LocalDate deliveryDate, String taskUuid, String projectUuid,
                                   String contractUuid, String contractProjectUuid,
                                   String contractConsultantUuid, BigDecimal normalizedDuration,
                                   BigDecimal normalizedRate, BigDecimal deliveryValue,
                                   RateResolutionStatus rateResolutionStatus) {
        public boolean usableForContribution() {
            return rateResolutionStatus == RateResolutionStatus.RESOLVED;
        }

        public ResolvedDelivery(String workUuid, String registrantUuid, String effectiveConsultantUuid,
                                LocalDate deliveryDate, String taskUuid, String projectUuid,
                                String contractUuid, BigDecimal normalizedDuration,
                                BigDecimal normalizedRate, BigDecimal deliveryValue,
                                RateResolutionStatus rateResolutionStatus) {
            this(workUuid, registrantUuid, effectiveConsultantUuid, deliveryDate, taskUuid, projectUuid,
                    contractUuid, null, null, normalizedDuration, normalizedRate, deliveryValue,
                    rateResolutionStatus);
        }
    }

    public enum RateResolutionStatus { RESOLVED, MISSING, AMBIGUOUS, INVALID }

    private record CanonicalCandidate(String workUuid, String registrantUuid, String effectiveConsultantUuid,
                                      LocalDate deliveryDate, String taskUuid, String projectUuid,
                                      String contractUuid, BigDecimal duration, BigDecimal rate,
                                      String contractProjectUuid, String contractConsultantUuid,
                                      RateResolutionStatus status) {
        static CanonicalCandidate invalid(String workUuid) {
            return new CanonicalCandidate(workUuid, null, null, null, null, null, null,
                    null, null, null, null, RateResolutionStatus.INVALID);
        }
    }
}
