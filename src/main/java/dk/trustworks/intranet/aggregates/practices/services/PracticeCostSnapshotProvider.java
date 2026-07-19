package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.ServiceUnavailableException;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical read seam for published practice salary, OPEX and FTE snapshots.
 *
 * <p>The legacy operating-cost endpoint, cost publisher, revenue publisher and contribution
 * reader all consume this provider.  The serving switch is intentionally evaluated before any
 * financial value is returned so an integrity incident can never surface a stale pointer as a
 * legitimate zero.</p>
 */
@JBossLog
@ApplicationScoped
public class PracticeCostSnapshotProvider {

    static final String SERVING_CONTROL_SQL = """
            SELECT c.legacy_cost_serving_enabled,
                   p.refresh_state, p.active_refresh_token, p.generation_at, p.published_at,
                   p.practice_basis_generation_id, p.certified_cost_basis_request_id,
                   p.certified_cost_basis_request_vector, p.cost_content_fingerprint,
                   p.opex_row_count, p.fte_row_count, p.completeness_row_count,
                   b.status, b.coverage_start_date, b.source_fingerprint,
                   b.capacity_source_fingerprint, b.dependency_manifest_fingerprint,
                   (SELECT COUNT(*) FROM fact_practice_cost_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT COUNT(*) FROM fact_practice_fte_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT COUNT(*) FROM fact_practice_cost_completeness_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT COUNT(DISTINCT content_fingerprint) FROM fact_practice_cost_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT COUNT(DISTINCT content_fingerprint) FROM fact_practice_fte_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT COUNT(DISTINCT content_fingerprint) FROM fact_practice_cost_completeness_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT MIN(content_fingerprint) FROM fact_practice_cost_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT MIN(content_fingerprint) FROM fact_practice_fte_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id),
                   (SELECT MIN(content_fingerprint) FROM fact_practice_cost_completeness_generation_mat x
                     WHERE x.generation_id=p.practice_basis_generation_id)
            FROM practice_contribution_publication_control c
            JOIN practice_operating_cost_publication p ON p.publication_id=1
            LEFT JOIN practice_basis_generation b ON b.generation_id=p.practice_basis_generation_id
            WHERE c.control_id = 1
            """;

    @Inject
    EntityManager em;

    @Inject
    CxoPracticeOperatingCostService snapshotReader;

    public Snapshot getSnapshot(CostSource requestedCostSource) {
        CostSource costSource = requestedCostSource == null ? CostSource.BOOKED : requestedCostSource;
        IntegrityPointer before = loadIntegrityPointer();
        if (!before.servingEnabled()) {
            return new Snapshot(unavailableResponse(costSource), false);
        }
        PracticeOperatingCostResponseDTO response = before.basisGenerationId() == null
                ? snapshotReader.readPublishedSnapshot(costSource)
                : snapshotReader.readPublishedCanonicalSnapshot(
                        costSource, before.basisGenerationId(), before.generationAt(), before.coverageStart());
        IntegrityPointer after = loadIntegrityPointer();
        if (!before.equals(after)) {
            log.warn("practice cost publication changed during canonical snapshot read");
            throw new ServiceUnavailableException("Operating-cost publication is unavailable.");
        }
        return new Snapshot(response, true);
    }

    private IntegrityPointer loadIntegrityPointer() {
        Query query = em.createNativeQuery(SERVING_CONTROL_SQL);
        query.setHint("jakarta.persistence.query.timeout", CxoSqlSupport.CXO_QUERY_TIMEOUT_MS);
        @SuppressWarnings("unchecked")
        List<Object> values = query.getResultList();
        if (values.size() != 1) {
            log.warn("practice cost serving control is missing or duplicated");
            throw new ServiceUnavailableException("Operating-cost publication is unavailable.");
        }
        Object[] row = (Object[]) values.getFirst();
        boolean enabled = bool(row[0]);
        if (!enabled) return IntegrityPointer.disabled();
        if (row[5] == null) {
            if (!"READY".equals(text(row[1])) || row[2] != null || row[3] == null || row[4] == null) {
                throw new ServiceUnavailableException("Operating-cost publication is unavailable.");
            }
            return new IntegrityPointer(true, null, toInstant(row[3]), null,
                    hashPointer(row[1], row[3], row[4], row[9], row[10], row[11]), null, null);
        }
        String costFingerprint = text(row[8]);
        String candidateFingerprint = text(row[23]);
        boolean coherent = "READY".equals(text(row[1])) && row[2] == null
                && row[3] != null && row[4] != null && row[5] != null
                && row[6] != null && row[7] != null && costFingerprint != null
                && "READY".equals(text(row[12])) && row[13] != null
                && row[14] != null && row[15] != null && row[16] != null
                && number(row[9]).longValue() == number(row[17]).longValue()
                && number(row[10]).longValue() == number(row[18]).longValue()
                && number(row[11]).longValue() == number(row[19]).longValue()
                && number(row[17]).longValue() > 0 && number(row[18]).longValue() > 0
                && number(row[19]).longValue() > 0
                && number(row[20]).intValue() == 1 && number(row[21]).intValue() == 1
                && number(row[22]).intValue() == 1 && candidateFingerprint != null
                && candidateFingerprint.equals(text(row[24])) && candidateFingerprint.equals(text(row[25]))
                && costFingerprint.equals(fingerprint(row[14], row[15], row[16], candidateFingerprint));
        if (!coherent) {
            log.warn("practice cost immutable generation failed integrity certification");
            throw new ServiceUnavailableException("Operating-cost publication is unavailable.");
        }
        return new IntegrityPointer(true, text(row[5]), toInstant(row[3]), toDate(row[13]),
                costFingerprint, candidateFingerprint, text(row[7]));
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool ? bool
                : value instanceof Number number ? number.intValue() != 0
                : Boolean.parseBoolean(String.valueOf(value));
    }
    private static Number number(Object value) { return (Number) value; }
    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
        return ((Timestamp) value).toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
    private static LocalDate toDate(Object value) {
        return value instanceof LocalDate date ? date : ((java.sql.Date) value).toLocalDate();
    }
    private static String hashPointer(Object... values) {
        return java.util.Arrays.deepToString(values);
    }
    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static PracticeOperatingCostResponseDTO unavailableResponse(CostSource source) {
        List<PracticeOperatingCostDTO> rows = CxoPracticeOperatingCostService.PRACTICES.stream()
                .map(practice -> new PracticeOperatingCostDTO(
                        practice,
                        null, null, null,
                        null, null, null,
                        null, null,
                        null, null,
                        null, null, null, null))
                .toList();
        return new PracticeOperatingCostResponseDTO(
                source.name(),
                null, null, null, null, null, null,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
                false, false, "UNAVAILABLE",
                false, false, "UNAVAILABLE",
                "UNAVAILABLE", false,
                "EFFECTIVE_DATED_PRACTICE", null,
                "Operating-cost values are withheld while the cost-integrity serving gate is disabled.",
                rows);
    }

    public record Snapshot(PracticeOperatingCostResponseDTO response, boolean servingEnabled) {
        public Snapshot {
            if (response == null) throw new IllegalArgumentException("response is required");
        }
    }

    private record IntegrityPointer(boolean servingEnabled, String basisGenerationId,
                                    Instant generationAt, LocalDate coverageStart,
                                    String costFingerprint, String candidateFingerprint,
                                    String certifiedRequestVector) {
        static IntegrityPointer disabled() {
            return new IntegrityPointer(false, null, null, null, null, null, null);
        }
    }
}
