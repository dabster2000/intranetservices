package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope of {@code GET/PUT /recruitment/interviews/rooms/policy} — which
 * meeting rooms the AI-assisted interview planners may book, and in what
 * order they prefer them (V513).
 * <p>
 * Entries are ordered exactly as the planners will consider them: ascending
 * priority, ties by name. Org resource data only — no PII.
 *
 * @param rooms          every room, preferred first, disabled ones included
 *                       so the settings page can offer them back
 * @param graphAvailable false when the Graph calendar toggle is off or the
 *                       room lookup failed. The settings page must then say
 *                       so rather than render an empty list as "no rooms
 *                       exist" — the stored policy is still shown, since a
 *                       Graph outage is not a reason to hide the admin's
 *                       own configuration.
 */
public record MeetingRoomPolicyResponse(List<RoomPolicyEntry> rooms, boolean graphAvailable) {

    /**
     * One room's standing.
     *
     * @param emailAddress  the room mailbox — the stable identity, and the
     *                      key a save refers back to
     * @param displayName   Graph's name for the room, or the last one seen
     *                      when the room has vanished from Graph
     * @param capacity      seats, {@code null} when Exchange has none set.
     *                      Null never excludes the room from automation — it
     *                      only means capacity cannot prove the room big
     *                      enough. Shown so an admin can rank sensibly, and
     *                      so a missing capacity is visible rather than
     *                      mysterious.
     * @param building      Graph's building, {@code null} when unset
     * @param enabled       whether the AI planners may pick this room. The
     *                      manual room picker is unaffected — a recruiter can
     *                      still book a disabled room by hand.
     * @param priority      1-based preference; lower is preferred
     * @param configured    false for a room Graph reports that the policy has
     *                      never seen — i.e. created in Exchange after the
     *                      initial seed. It is NOT bookable by automation
     *                      until someone enables it, so the UI flags it.
     * @param presentInGraph false for a policy row whose room Graph no longer
     *                      reports (deleted resource). Kept and shown rather
     *                      than dropped, so "deleted in Exchange" is
     *                      distinguishable from "we lost your setting".
     */
    public record RoomPolicyEntry(
            String emailAddress,
            String displayName,
            Integer capacity,
            String building,
            boolean enabled,
            int priority,
            boolean configured,
            boolean presentInGraph
    ) {
    }
}
