package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code PUT /recruitment/interviews/rooms/policy} — the complete
 * meeting-room policy for AI-assisted scheduling (V513).
 * <p>
 * Whole-list replacement, not a patch: a drag-and-drop reorder changes many
 * priorities at once, and splitting that into per-room writes would let a
 * half-applied save leave the ordering in a state nobody chose.
 *
 * @param orderedRoomEmails every room mailbox in preference order, most
 *                          preferred first. Position IS the priority, so the
 *                          client never sends priority numbers and can never
 *                          submit a contradictory pair. Rooms omitted here
 *                          keep their row but are switched off for
 *                          automation and pushed below everything ranked —
 *                          a Graph blip during a save must not silently
 *                          delete the admin's list.
 * @param enabledRoomEmails the subset the planners may actually book. Must be
 *                          contained in {@code orderedRoomEmails}; an enabled
 *                          room with no position would have no defined
 *                          preference, which is exactly the ambiguity this
 *                          feature removes.
 */
public record MeetingRoomPolicyRequest(
        @NotNull(message = "orderedRoomEmails is required")
        @Size(max = 500, message = "Too many rooms in one save")
        // Container-element constraints, not just a list-level one: the
        // address becomes a VARCHAR(255) PRIMARY KEY, so an over-long value
        // would surface as a data-truncation 500 rather than a 400 naming the
        // offending field.
        List<@Size(max = 255, message = "Room mailbox is too long") String> orderedRoomEmails,

        @NotNull(message = "enabledRoomEmails is required")
        @Size(max = 500, message = "Too many rooms in one save")
        List<@Size(max = 255, message = "Room mailbox is too long") String> enabledRoomEmails
) {
}
