package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomsResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentMeetingRoomPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Seeds {@code recruitment_meeting_room_policy} from Graph the first time it
 * is read while empty (V513).
 *
 * <h2>Why a separate bean</h2>
 * The seed must run in its OWN transaction: it is triggered from read paths
 * that may not have one, and a seed failure must not poison the caller's.
 * {@code REQUIRES_NEW} only takes effect through the CDI proxy, so calling it
 * from a sibling method of the same class would silently do nothing — the
 * self-invocation trap that made the e-conomic importer's
 * {@code REQUIRES_NEW} dead code. A separate injected bean is the reliable
 * way to get a genuinely new transaction.
 *
 * <h2>Why seed at all</h2>
 * The policy is a whitelist — a room with no row is not bookable by
 * automation — so an empty table means the AI books no rooms whatsoever.
 * That is correct for a room created in Exchange after the fact, and wrong
 * for the moment this feature is deployed. Seeding every currently-known
 * room as enabled makes the deploy a no-op for schedulers, and the standing
 * "new room ⇒ disabled" rule applies from then on.
 *
 * <p>The seed cannot live in the Flyway migration: the room list only exists
 * behind a live Graph call, and SQL cannot make one.
 */
@ApplicationScoped
public class RecruitmentMeetingRoomPolicyBootstrap {

    private static final Logger log =
            Logger.getLogger(RecruitmentMeetingRoomPolicyBootstrap.class);

    /**
     * Seed the policy from {@code graphRooms} if and only if no row exists.
     *
     * <p>Idempotent by design, which is what makes it safe on the read path
     * and safe after the staging nightly data refresh drops the rows: it
     * simply re-seeds rather than leaving the planners with nothing.
     *
     * @return the rows as they now stand — the freshly seeded ones, or the
     *         rows a concurrent request seeded first
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<RecruitmentMeetingRoomPolicy> seedIfEmpty(
            List<MeetingRoomsResponse.MeetingRoom> graphRooms) {
        // Re-checked INSIDE the new transaction: two concurrent first reads
        // would otherwise both decide to seed and the loser would die on the
        // primary key.
        List<RecruitmentMeetingRoomPolicy> rows = RecruitmentMeetingRoomPolicy.listAll();
        if (!rows.isEmpty()) {
            return rows; // another request won the race — its seed stands
        }
        if (graphRooms == null || graphRooms.isEmpty()) {
            return List.of();
        }
        List<MeetingRoomsResponse.MeetingRoom> seedable = graphRooms.stream()
                .filter(room -> room.emailAddress() != null && !room.emailAddress().isBlank())
                .sorted(Comparator.comparing(
                        RecruitmentMeetingRoomPolicyBootstrap::labelOf,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<RecruitmentMeetingRoomPolicy> seeded = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int priority = 1;
        for (MeetingRoomsResponse.MeetingRoom room : seedable) {
            String email = RecruitmentMeetingRoomPolicy.normalizeEmail(room.emailAddress());
            if (!seen.add(email)) {
                continue; // Graph can echo an alias twice; one row per mailbox
            }
            RecruitmentMeetingRoomPolicy row = new RecruitmentMeetingRoomPolicy(
                    email, room.displayName(), true, priority++);
            row.persist();
            seeded.add(row);
        }
        log.infov("Meeting-room policy seeded from Graph: {0} room(s), all enabled for automation",
                seeded.size());
        return seeded;
    }

    /** Sort label — display name, falling back to the mailbox. */
    private static String labelOf(MeetingRoomsResponse.MeetingRoom room) {
        return room.displayName() != null && !room.displayName().isBlank()
                ? room.displayName() : room.emailAddress();
    }
}
