package dk.trustworks.intranet.aggregates.people.dto.cxo;

import java.util.List;

/**
 * Current consultant headcount distribution across pyramid buckets returned
 * by GET /people/cxo/consultant-pyramid.
 *
 * <p>Mirrors the {@code ConsultantPyramidDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. Source: active consultants
 * ({@code userstatus.type='CONSULTANT', status='ACTIVE'}) with their most
 * recent {@code user_career_level} entry, grouped into 5 hardcoded buckets:
 * Junior, Mid, Senior, Leadership, Partner. Unmapped career levels (e.g.
 * SENIOR_CONSULTANT, LEAD_CONSULTANT, MANAGING_CONSULTANT,
 * PRINCIPAL_CONSULTANT) contribute to {@code totalConsultants} (matching
 * BFF semantics — the BFF accumulates the total unconditionally) but are
 * not counted in any bucket.</p>
 *
 * <p>{@code snapshotDate} is the ISO-8601 date the snapshot was taken
 * server-side ({@code LocalDate.now()}, formatted as YYYY-MM-DD).</p>
 */
public record ConsultantPyramidDTO(
        List<PyramidLevelDTO> levels,
        long totalConsultants,
        String snapshotDate
) {
    public ConsultantPyramidDTO {
        if (levels == null) throw new IllegalArgumentException("levels must not be null");
        if (totalConsultants < 0)
            throw new IllegalArgumentException("totalConsultants must be non-negative: " + totalConsultants);
        if (snapshotDate == null || !snapshotDate.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new IllegalArgumentException("snapshotDate must be ISO YYYY-MM-DD, was " + snapshotDate);
        // Defensive copy for immutability.
        levels = List.copyOf(levels);
    }
}
