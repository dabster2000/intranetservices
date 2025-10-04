package dk.trustworks.intranet.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Represents an overlap between consultant assignments for the same project.
 * Used to provide detailed information about conflicting contracts.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContractOverlap {

    // Existing contract information
    private String existingContractUuid;
    private String existingContractName;
    private String existingConsultantUuid;
    private LocalDate existingActiveFrom;
    private LocalDate existingActiveTo;
    private double existingRate;

    // New/proposed contract information
    private String newContractUuid;
    private String newContractName;
    private String newConsultantUuid;
    private LocalDate newActiveFrom;
    private LocalDate newActiveTo;
    private double newRate;

    // Shared information
    private String consultantUuid;
    private String consultantName;
    private String projectUuid;
    private String projectName;

    // Overlap details
    private LocalDate overlapStart;
    private LocalDate overlapEnd;
    private int overlapDays;
    private boolean rateConflict;

    /**
     * Calculate the overlap period between two date ranges.
     */
    public void calculateOverlap() {
        // Find the latest start date
        overlapStart = existingActiveFrom.isAfter(newActiveFrom) ? existingActiveFrom : newActiveFrom;

        // Find the earliest end date
        overlapEnd = existingActiveTo.isBefore(newActiveTo) ? existingActiveTo : newActiveTo;

        // Calculate days of overlap
        if (overlapStart.isBefore(overlapEnd) || overlapStart.equals(overlapEnd)) {
            overlapDays = (int) java.time.temporal.ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
        } else {
            overlapDays = 0;
        }

        // Check if rates differ
        rateConflict = Math.abs(existingRate - newRate) > 0.01;
    }

    /**
     * Get a human-readable description of the overlap.
     */
    public String getDescription() {
        StringBuilder desc = new StringBuilder();
        desc.append("Consultant ").append(consultantName)
            .append(" is already assigned to project ").append(projectName)
            .append(" from ").append(existingActiveFrom)
            .append(" to ").append(existingActiveTo)
            .append(" with rate ").append(existingRate);

        if (rateConflict) {
            desc.append(". New rate ").append(newRate).append(" conflicts with existing rate.");
        }

        desc.append(" Overlap period: ").append(overlapStart)
            .append(" to ").append(overlapEnd)
            .append(" (").append(overlapDays).append(" days)");

        return desc.toString();
    }

    /**
     * Check if this is a complete overlap (one period entirely contains the other).
     */
    public boolean isCompleteOverlap() {
        return (existingActiveFrom.compareTo(newActiveFrom) <= 0 && existingActiveTo.compareTo(newActiveTo) >= 0) ||
               (newActiveFrom.compareTo(existingActiveFrom) <= 0 && newActiveTo.compareTo(existingActiveTo) >= 0);
    }

    /**
     * Check if this is a partial overlap.
     */
    public boolean isPartialOverlap() {
        return overlapDays > 0 && !isCompleteOverlap();
    }
}