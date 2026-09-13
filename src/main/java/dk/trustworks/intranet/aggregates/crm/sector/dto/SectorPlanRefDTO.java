package dk.trustworks.intranet.aggregates.crm.sector.dto;

import java.time.LocalDate;

/**
 * The sector plan as a chip: whether one exists, its colour and when it was last confirmed.
 * {@code rag} is null when nobody has assessed it — never a blank green.
 */
public record SectorPlanRefDTO(boolean started, String rag, LocalDate updatedAt, LocalDate nextReview, int version) {
}
