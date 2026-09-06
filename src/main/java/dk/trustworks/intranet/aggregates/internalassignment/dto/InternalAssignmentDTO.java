package dk.trustworks.intranet.aggregates.internalassignment.dto;

import dk.trustworks.intranet.aggregates.internalassignment.model.InternalAssignment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** An internal assignment as the profile, the approval queue and Staffing read it. */
public record InternalAssignmentDTO(
        String uuid,
        String useruuid,
        String title,
        String sponsorUseruuid,
        LocalDate activeFrom,
        LocalDate activeTo,
        BigDecimal hoursPerWeek,
        BigDecimal estimatedTotalHours,
        boolean strategic,
        String status,
        String approvedBy,
        LocalDateTime approvedAt,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static InternalAssignmentDTO from(InternalAssignment row) {
        return new InternalAssignmentDTO(
                row.getUuid(),
                row.getUseruuid(),
                row.getTitle(),
                row.getSponsorUseruuid(),
                row.getActiveFrom(),
                row.getActiveTo(),
                row.getHoursPerWeek(),
                row.getEstimatedTotalHours(),
                row.isStrategic(),
                row.getStatus() == null ? null : row.getStatus().name(),
                row.getApprovedBy(),
                row.getApprovedAt(),
                row.getNotes(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getUpdatedBy()
        );
    }
}
