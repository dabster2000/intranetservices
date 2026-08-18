package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.MailboxWindowSchedule;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.RoomOption;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Picks the meeting room for one interview slot — the single implementation
 * behind BOTH automatic planners (Method A's {@link AvailabilitySlotSuggester}
 * and Method B's {@link MultiSlotPlanner}), which each carried a verbatim
 * copy of the old rule until they were merged here.
 *
 * <h2>The rule</h2>
 * Walk {@code rooms} in the given order and take the FIRST room that is both
 * big enough and known free. The order is the admin's priority list
 * ({@code recruitment_meeting_room_policy}, V513) — the caller has already
 * filtered out disabled rooms and sorted by priority, so this class never
 * needs to know the policy exists.
 *
 * <h2>What changed, and why</h2>
 * The previous rule was "smallest room that seats the headcount", implemented
 * as {@code filter(capacity >= headcount).min(by capacity)}. Two properties of
 * that made room choice look arbitrary and land on the same room forever:
 * <ul>
 *   <li>the smallest fitting room always won, so a 2-person interview could
 *       never be put in a bigger room even when every room was free; and</li>
 *   <li>{@code Stream.min} returns the FIRST minimum, so among equal-capacity
 *       rooms the winner was decided by Graph's list order — stable, invisible
 *       and unchangeable by anyone at Trustworks.</li>
 * </ul>
 * Capacity is now a FLOOR, not the ranking key: it can only rule a room out
 * for being too small. Which of the acceptable rooms wins is the admin's
 * explicit decision.
 *
 * <h2>Unknown capacity</h2>
 * Graph reports no capacity for a room whose Exchange resource never had one
 * set. The old filter dropped those rooms outright, so rooms nobody had
 * finished configuring were silently invisible to automation. A null capacity
 * now clears the floor: it cannot PROVE the room is big enough, so it is not
 * treated as proof of the opposite either.
 *
 * <h2>Unknown availability</h2>
 * Rooms keep the strict half of the house free/busy posture: a room is only
 * offered when its schedule is KNOWN and free. "Unknown never counts as busy"
 * is right for marking a person and wrong for a room, because a suggested room
 * reads as a booking promise. This is deliberately the opposite of the
 * interviewer rule in {@link AvailabilitySlotSuggester#viewFree}.
 *
 * <p>Pure and CDI-free so it is covered by plain unit tests in the DB-free
 * tier that gates deploys.
 */
public final class MeetingRoomPicker {

    private MeetingRoomPicker() {
    }

    /**
     * The chosen room plus why it was chosen — {@code rank} is its 1-based
     * position in the offered priority order and {@code skipped} counts the
     * higher-ranked rooms that were passed over. Surfaced to the scheduler so
     * the choice stops reading as arbitrary ("HP1 — preference 2; 1
     * preferred room was unavailable").
     */
    public record Pick(RoomOption room, int rank, int skippedUnavailable, int skippedTooSmall) {

        /** Total higher-ranked rooms passed over for any reason. */
        public int skipped() {
            return skippedUnavailable + skippedTooSmall;
        }

        /**
         * A short, non-PII explanation for the UI. Null when the top-ranked
         * room simply won — there is nothing to explain about that.
         * <p>
         * "Unavailable" rather than "busy" on purpose: a room is skipped both
         * when its calendar says busy AND when we could not read its calendar
         * at all. Calling the second case "busy" would state as fact something
         * we never established.
         */
        public String reason() {
            if (skipped() == 0) {
                return null;
            }
            StringBuilder why = new StringBuilder("preference ").append(rank).append(" — ");
            if (skippedUnavailable > 0) {
                why.append(skippedUnavailable).append(skippedUnavailable == 1
                        ? " preferred room was unavailable"
                        : " preferred rooms were unavailable");
            }
            if (skippedTooSmall > 0) {
                if (skippedUnavailable > 0) {
                    why.append(", ");
                }
                why.append(skippedTooSmall).append(skippedTooSmall == 1
                        ? " was too small" : " were too small");
            }
            return why.toString();
        }
    }

    /**
     * Pick a room for {@code [slotStart, slotStart + durationMinutes)}.
     *
     * @param windowStart     the moment digit index 0 of every availability
     *                        view refers to — the same anchor the caller used
     *                        when probing Graph
     * @param schedules       per-mailbox schedules keyed by LOWERCASE address
     * @param rooms           candidate rooms, already filtered to the enabled
     *                        set and sorted best-first by the admin's priority
     * @param slotStart       start of the slot to fill
     * @param durationMinutes slot length
     * @param headcount       people to seat (interviewers + candidate)
     * @return the pick, or {@code null} when no offered room is both big
     *         enough and known free — never an exception, because a missing
     *         room costs a room, never the slot
     */
    public static Pick pick(LocalDateTime windowStart,
                            Map<String, MailboxWindowSchedule> schedules,
                            List<RoomOption> rooms,
                            LocalDateTime slotStart,
                            int durationMinutes,
                            int headcount) {
        if (rooms == null || rooms.isEmpty()) {
            return null;
        }
        int skippedUnavailable = 0;
        int skippedTooSmall = 0;
        for (int index = 0; index < rooms.size(); index++) {
            RoomOption room = rooms.get(index);
            if (room == null || room.email() == null) {
                continue;
            }
            // Capacity is a FLOOR, never the ranking key. Null = unknown,
            // which disqualifies nothing (see the class note).
            if (room.capacity() != null && room.capacity() < headcount) {
                skippedTooSmall++;
                continue;
            }
            MailboxWindowSchedule schedule = schedules == null
                    ? null : schedules.get(room.email());
            boolean known = schedule != null && schedule.availabilityView() != null;
            if (!known || !AvailabilitySlotSuggester.viewFree(windowStart,
                    schedule.availabilityView(), slotStart, durationMinutes, false)) {
                // Unknown counts as unusable for rooms, on purpose: an
                // unverifiable room must not be turned into a promise.
                skippedUnavailable++;
                continue;
            }
            return new Pick(room, index + 1, skippedUnavailable, skippedTooSmall);
        }
        return null;
    }
}
