package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomPolicyResponse;
import dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomsResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentMeetingRoomPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Which meeting rooms the AI-assisted interview planners may book, and in
 * what order they prefer them (V513).
 *
 * <h2>Why this exists</h2>
 * Room choice used to be emergent: both planners took the smallest room that
 * seated the headcount, and ties fell to Graph's list order — so in
 * production every AI-created invitation landed in the same small room, and
 * nobody at Trustworks had a way to say otherwise. This service makes the
 * choice an owned decision: an ordered, explicitly enabled list.
 *
 * <h2>The enabled set is a whitelist</h2>
 * A room absent from the table is NOT bookable by automation (owner decision
 * 2026-08-18): a room created in Exchange later must be ranked deliberately
 * rather than quietly joining the rotation. That rule alone would have made
 * the feature ship broken — an empty table means no rooms at all — so
 * {@link RecruitmentMeetingRoomPolicyBootstrap} seeds every currently-known
 * room as enabled the first time the policy is read. After that seed, "new
 * room ⇒ disabled" is the standing rule.
 *
 * <p>The whitelist governs AUTOMATION only. The room picker a recruiter
 * operates by hand still lists every room the tenant has — disabling a room
 * is a statement about what the AI may reach for, not about what people may
 * book.
 *
 * <h2>Dependency direction</h2>
 * The tenant's rooms are passed IN rather than fetched here, so this bean
 * depends on nothing but its own table. That keeps
 * {@link RecruitmentCalendarService} → policy a one-way edge (the calendar
 * service filters its own room list through this one) instead of a CDI
 * cycle, and lets the whole ranking be unit-tested without Graph.
 *
 * <h2>Failure posture</h2>
 * Every read degrades the way the rest of the calendar integration does: no
 * rooms, never an error, and scheduling continues without one. The one thing
 * this service will not do is fall back to "all rooms" when the policy cannot
 * be read — that would quietly resurrect the behaviour it exists to replace.
 */
@ApplicationScoped
public class RecruitmentMeetingRoomPolicyService {

    private static final Logger log =
            Logger.getLogger(RecruitmentMeetingRoomPolicyService.class);

    /** Priority given to rooms the policy has never ranked. */
    static final int UNRANKED_PRIORITY = 1000;

    /** Injected, not self-invoked: {@code REQUIRES_NEW} only takes effect
     * through the CDI proxy. */
    @Inject
    RecruitmentMeetingRoomPolicyBootstrap bootstrap;

    /**
     * The rooms the planners may offer, best-first.
     * <p>
     * Called on every suggestion pass: one indexed read plus an in-memory
     * join against the room list the caller already holds.
     *
     * @param graphRooms the tenant's rooms as Graph reported them
     * @return enabled rooms only, ordered by ascending priority; empty when
     *         nothing is enabled — the planners then suggest slots with no
     *         room, which is the pre-existing no-room-available behaviour
     */
    public List<MeetingRoomsResponse.MeetingRoom> enabledRoomsInPriorityOrder(
            List<MeetingRoomsResponse.MeetingRoom> graphRooms) {
        if (graphRooms == null || graphRooms.isEmpty()) {
            return List.of();
        }
        Map<String, RecruitmentMeetingRoomPolicy> policy;
        try {
            policy = policyByEmail(graphRooms);
        } catch (Exception e) {
            // Never widen to "all rooms" on failure — that is exactly the
            // unowned behaviour this service replaces. No policy, no rooms.
            log.warnv("Meeting-room policy read failed: {0} — offering no rooms to automation",
                    e.getMessage());
            return List.of();
        }
        record Ranked(MeetingRoomsResponse.MeetingRoom room, int priority, String label) { }
        return graphRooms.stream()
                .filter(room -> room.emailAddress() != null)
                .map(room -> {
                    RecruitmentMeetingRoomPolicy row = policy.get(
                            RecruitmentMeetingRoomPolicy.normalizeEmail(room.emailAddress()));
                    return row != null && row.isEnabled()
                            ? new Ranked(room, row.getPriority(), labelOf(room)) : null;
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(Ranked::priority)
                        // Ties cannot happen after a save (a save writes a
                        // dense 1..n) but CAN after a partial write or a hand
                        // edit. Break them by name, so the order stays stable
                        // and explicable — never by Graph's list order, which
                        // is the invisible tiebreak that caused the original
                        // defect.
                        .thenComparing(Ranked::label, String.CASE_INSENSITIVE_ORDER))
                .map(Ranked::room)
                .toList();
    }

    /**
     * The settings view: every room Graph currently reports joined with its
     * policy row, plus any policy row whose room has vanished from Graph
     * (deleted resource) so an admin can see and clean it up.
     *
     * @param graphRooms     the tenant's rooms, or empty when Graph is off
     *                       or unreachable
     * @param graphAvailable whether {@code graphRooms} is a trustworthy
     *                       picture of the tenant — an empty list means
     *                       something different in each case
     */
    public MeetingRoomPolicyResponse currentPolicy(
            List<MeetingRoomsResponse.MeetingRoom> graphRooms, boolean graphAvailable) {
        List<MeetingRoomsResponse.MeetingRoom> rooms =
                graphRooms == null ? List.of() : graphRooms;
        Map<String, RecruitmentMeetingRoomPolicy> policy = policyByEmail(rooms);

        List<MeetingRoomPolicyResponse.RoomPolicyEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MeetingRoomsResponse.MeetingRoom room : rooms) {
            if (room.emailAddress() == null) {
                continue;
            }
            String key = RecruitmentMeetingRoomPolicy.normalizeEmail(room.emailAddress());
            if (!seen.add(key)) {
                continue; // Graph can echo an alias twice
            }
            RecruitmentMeetingRoomPolicy row = policy.get(key);
            entries.add(new MeetingRoomPolicyResponse.RoomPolicyEntry(
                    room.emailAddress(), labelOf(room), room.capacity(), room.building(),
                    row != null && row.isEnabled(),
                    row != null ? row.getPriority() : UNRANKED_PRIORITY,
                    row != null,
                    true));
        }
        // Policy rows Graph no longer knows: surfaced, never silently dropped
        // — an admin should be able to tell "deleted in Exchange" apart from
        // "we lost your setting".
        for (RecruitmentMeetingRoomPolicy row : policy.values()) {
            if (seen.contains(row.getRoomEmail())) {
                continue;
            }
            entries.add(new MeetingRoomPolicyResponse.RoomPolicyEntry(
                    row.getRoomEmail(), row.getDisplayName(), null, null,
                    row.isEnabled(), row.getPriority(), true, false));
        }
        entries.sort(Comparator
                .comparingInt(MeetingRoomPolicyResponse.RoomPolicyEntry::priority)
                .thenComparing(entry -> entry.displayName() == null
                                ? entry.emailAddress() : entry.displayName(),
                        String.CASE_INSENSITIVE_ORDER));
        return new MeetingRoomPolicyResponse(entries, graphAvailable);
    }

    /**
     * Replace the whole policy: position in {@code orderedEmails} IS the
     * priority (1-based), and only {@code enabledEmails} may be booked by
     * automation.
     * <p>
     * Whole-list replacement rather than per-room PATCH on purpose: a
     * drag-and-drop reorder changes many priorities at once, and expressing
     * that as N independent writes would let a half-applied save leave the
     * ordering in a state nobody chose.
     *
     * @param graphRooms the tenant's rooms, used only to refresh the stored
     *                   display-name snapshots; may be empty
     * @return the policy as it now stands, for the caller to echo back
     */
    @Transactional
    public MeetingRoomPolicyResponse replacePolicy(
            List<String> orderedEmails, Set<String> enabledEmails,
            List<MeetingRoomsResponse.MeetingRoom> graphRooms, boolean graphAvailable) {
        if (orderedEmails == null) {
            throw new BadRequestException("A room order must be supplied");
        }
        // LinkedHashSet: keep the submitted order, but let a duplicated
        // address collapse rather than produce two rows fighting over one
        // primary key.
        LinkedHashSet<String> ordered = orderedEmails.stream()
                .map(RecruitmentMeetingRoomPolicy::normalizeEmail)
                .filter(email -> email != null && !email.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> enabled = enabledEmails == null ? Set.of()
                : enabledEmails.stream()
                        .map(RecruitmentMeetingRoomPolicy::normalizeEmail)
                        .filter(email -> email != null && !email.isBlank())
                        .collect(Collectors.toSet());
        if (!ordered.containsAll(enabled)) {
            throw new BadRequestException(
                    "Every enabled room must also appear in the priority order");
        }

        Map<String, RecruitmentMeetingRoomPolicy> existing =
                RecruitmentMeetingRoomPolicy.<RecruitmentMeetingRoomPolicy>listAll().stream()
                        .collect(Collectors.toMap(RecruitmentMeetingRoomPolicy::getRoomEmail,
                                Function.identity(), (first, second) -> first));
        Map<String, String> namesByEmail = (graphRooms == null ? List.<MeetingRoomsResponse.MeetingRoom>of() : graphRooms)
                .stream()
                .filter(room -> room.emailAddress() != null && room.displayName() != null)
                .collect(Collectors.toMap(
                        room -> RecruitmentMeetingRoomPolicy.normalizeEmail(room.emailAddress()),
                        MeetingRoomsResponse.MeetingRoom::displayName,
                        (first, second) -> first));

        int priority = 1;
        for (String email : ordered) {
            RecruitmentMeetingRoomPolicy row = existing.remove(email);
            if (row == null) {
                row = new RecruitmentMeetingRoomPolicy(email, namesByEmail.get(email),
                        enabled.contains(email), priority);
                row.persist();
            } else {
                row.setEnabled(enabled.contains(email));
                row.setPriority(priority);
                if (namesByEmail.containsKey(email)) {
                    row.setDisplayName(namesByEmail.get(email));
                }
            }
            priority++;
        }
        // Rows the save did not mention: kept (they may be rooms Graph was
        // merely unreachable for while the page was open) but switched off
        // and pushed below everything ranked. Deleting them here would let
        // one Graph blip erase the admin's whole list.
        for (RecruitmentMeetingRoomPolicy orphan : existing.values()) {
            orphan.setEnabled(false);
            orphan.setPriority(UNRANKED_PRIORITY);
        }
        log.infov("Meeting-room policy saved: {0} ranked, {1} enabled for automation, {2} unranked",
                ordered.size(), enabled.size(), existing.size());
        return currentPolicy(graphRooms, graphAvailable);
    }

    /**
     * The policy rows, seeding the table from Graph the first time it is read
     * while empty (see {@link RecruitmentMeetingRoomPolicyBootstrap} for why
     * the seed exists and why it lives in a bean of its own).
     */
    private Map<String, RecruitmentMeetingRoomPolicy> policyByEmail(
            List<MeetingRoomsResponse.MeetingRoom> graphRooms) {
        List<RecruitmentMeetingRoomPolicy> rows = RecruitmentMeetingRoomPolicy.listAll();
        if (rows.isEmpty() && graphRooms != null && !graphRooms.isEmpty()) {
            rows = bootstrap.seedIfEmpty(graphRooms);
        }
        return rows.stream().collect(Collectors.toMap(
                RecruitmentMeetingRoomPolicy::getRoomEmail, Function.identity(),
                (first, second) -> first));
    }

    /** Display label — Graph's name, with the mailbox as the fallback. */
    private static String labelOf(MeetingRoomsResponse.MeetingRoom room) {
        return room.displayName() != null && !room.displayName().isBlank()
                ? room.displayName() : room.emailAddress();
    }
}
