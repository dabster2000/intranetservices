package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Envelope of {@code GET /recruitment/interviews/suggested-slots} —
 * ranked interview slot candidates where every chosen interviewer is
 * free, inside the intersection of their working hours, weekdays only
 * (see {@code AvailabilitySlotSuggester} for the full rules). Empty when
 * the Graph calendar toggle is off or availability could not be read —
 * the UI then simply shows no suggestion chips, same degrade posture as
 * the room picker.
 * <p>
 * {@code availabilityComplete} is false when a chosen interviewer's
 * free/busy could not be read at all (Graph throttled or errored). An
 * empty {@code slots} then means "we did not dare guess", NOT "there is
 * nothing free" — the two look identical to a user otherwise, and
 * reporting the first as the second is how a throttled lookup used to
 * pass for a fully-booked fortnight (production 2026-08-15).
 */
public record SuggestedSlotsResponse(List<SuggestedSlot> slots,
                                     boolean availabilityComplete) {

    /**
     * One suggestion. {@code start} is wall-clock Europe/Copenhagen.
     * {@code roomEmail}/{@code roomDisplayName} carry the smallest free
     * room that seats everyone, or {@code null} when no known-free room
     * fits — the slot is still valid, the scheduler picks a location.
     */
    public record SuggestedSlot(
            LocalDateTime start,
            int durationMinutes,
            String roomEmail,
            String roomDisplayName
    ) {
    }
}
