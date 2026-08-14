package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.MailboxWindowSchedule;
import dk.trustworks.intranet.recruitmentservice.services.MultiSlotPlanner.ExternalConstraint;
import dk.trustworks.intranet.recruitmentservice.services.MultiSlotPlanner.PlanRequest;
import dk.trustworks.intranet.recruitmentservice.services.MultiSlotPlanner.PlannedSlot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec §12.3 precedence table as a DB-free matrix (plan §12.5),
 * driven through {@link AvailabilityConstraintResolver} +
 * {@link MultiSlotPlanner} together: busy wins, AVAILABLE_ONLY
 * intersects its covered days only, PREFERRED/AVOID rank and never
 * exclude, evidence applies inside its covered range only, expired and
 * unconfirmed evidence is ignored, and stale once-confirmed
 * AVAILABLE_ONLY claims trigger the finalization reconfirmation prompt.
 */
class AvailabilityConstraintMatrixTest {

    /** Monday 2026-08-17. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);
    private static final LocalDate TUESDAY = MONDAY.plusDays(1);
    private static final LocalDate WEDNESDAY = MONDAY.plusDays(2);
    private static final LocalDateTime EARLY = MONDAY.minusDays(7).atStartOfDay();
    private static final LocalDateTime NOW = MONDAY.atTime(6, 0);

    private static final String A = "a@trustworks.dk";

    // ------------------------------------------------------------------
    // Hard rules, straight through the planner
    // ------------------------------------------------------------------

    @Test
    void confirmedBusy_blocksExactlyLikeO365Busy() {
        // O365 free all day; a confirmed external BUSY 07:00–12:00.
        Map<String, List<ExternalConstraint>> external = resolve(
                evidence(EvidenceConfirmationStatus.CONFIRMED, MONDAY, MONDAY, null),
                constraint(AvailabilityConstraintType.BUSY,
                        MONDAY.atTime(7, 0), MONDAY.atTime(12, 0)));
        List<PlannedSlot> picks = plan(external, 1);
        assertEquals(MONDAY.atTime(12, 0), picks.getFirst().start(),
                "the first free hour must be after the external busy block");
    }

    @Test
    void busyWins_availableOnlyNeverReopensBusyTime() {
        // The same interval claimed BUSY by one evidence row and inside
        // an AVAILABLE_ONLY window by another: busy wins (spec §12.3).
        RecruitmentAvailabilityEvidence busyEvidence =
                evidence(EvidenceConfirmationStatus.CONFIRMED, MONDAY, MONDAY, null);
        RecruitmentAvailabilityEvidence availableEvidence =
                evidence(EvidenceConfirmationStatus.CONFIRMED, MONDAY, MONDAY, null);
        Map<String, List<ExternalConstraint>> external = resolveMany(
                Map.of(busyEvidence, List.of(row(busyEvidence,
                                AvailabilityConstraintType.BUSY,
                                MONDAY.atTime(7, 0), MONDAY.atTime(10, 0))),
                        availableEvidence, List.of(row(availableEvidence,
                                AvailabilityConstraintType.AVAILABLE_ONLY,
                                MONDAY.atTime(7, 0), MONDAY.atTime(10, 0)))));
        assertEquals(MultiSlotPlanner.ExternalVerdict.BLOCKED,
                MultiSlotPlanner.externalVerdict(external.get("user-1"),
                        MONDAY.atTime(8, 0), MONDAY.atTime(9, 0)));
    }

    @Test
    void availableOnly_restrictsItsCoveredDays_only() {
        // "Monday: only 13–15" covering Monday alone. Monday slots must
        // fit 13–15 (and a fit OVERRIDES O365 — F1a); Tuesday is
        // untouched (covered-range-only).
        Map<String, List<ExternalConstraint>> external = resolve(
                evidence(EvidenceConfirmationStatus.CONFIRMED, MONDAY, MONDAY, null),
                constraint(AvailabilityConstraintType.AVAILABLE_ONLY,
                        MONDAY.atTime(13, 0), MONDAY.atTime(15, 0)));
        List<ExternalConstraint> constraints = external.get("user-1");
        assertEquals(MultiSlotPlanner.ExternalVerdict.BLOCKED,
                MultiSlotPlanner.externalVerdict(constraints,
                        MONDAY.atTime(9, 0), MONDAY.atTime(10, 0)),
                "outside the window on a covered day");
        assertEquals(MultiSlotPlanner.ExternalVerdict.AVAILABLE_OVERRIDE,
                MultiSlotPlanner.externalVerdict(constraints,
                        MONDAY.atTime(13, 30), MONDAY.atTime(14, 30)),
                "inside the window — the stated period beats the calendar");
        assertEquals(MultiSlotPlanner.ExternalVerdict.NEUTRAL,
                MultiSlotPlanner.externalVerdict(constraints,
                        TUESDAY.atTime(9, 0), TUESDAY.atTime(10, 0)),
                "uncovered day is unrestricted");
    }

    @Test
    void adjacentAvailableOnlyWindows_mergeIntoOne() {
        // "9–12 og 12–15" must admit an 11–13 slot.
        RecruitmentAvailabilityEvidence row =
                evidence(EvidenceConfirmationStatus.CONFIRMED, MONDAY, MONDAY, null);
        Map<String, List<ExternalConstraint>> external = resolveMany(Map.of(row, List.of(
                row(row, AvailabilityConstraintType.AVAILABLE_ONLY,
                        MONDAY.atTime(9, 0), MONDAY.atTime(12, 0)),
                row(row, AvailabilityConstraintType.AVAILABLE_ONLY,
                        MONDAY.atTime(12, 0), MONDAY.atTime(15, 0)))));
        assertEquals(MultiSlotPlanner.ExternalVerdict.AVAILABLE_OVERRIDE,
                MultiSlotPlanner.externalVerdict(external.get("user-1"),
                        MONDAY.atTime(11, 0), MONDAY.atTime(13, 0)));
    }

    // ------------------------------------------------------------------
    // Soft rules — ranking only
    // ------------------------------------------------------------------

    @Test
    void preferredAndAvoid_rankButNeverExclude() {
        // PREFERRED 13–16, AVOID 07–10 — every slot stays feasible, but
        // a preferred-afternoon slot outranks the earlier start.
        RecruitmentAvailabilityEvidence row =
                evidence(EvidenceConfirmationStatus.CONFIRMED, MONDAY, MONDAY, null);
        Map<String, List<ExternalConstraint>> external = resolveMany(Map.of(row, List.of(
                row(row, AvailabilityConstraintType.PREFERRED,
                        MONDAY.atTime(13, 0), MONDAY.atTime(16, 0)),
                row(row, AvailabilityConstraintType.AVOID,
                        MONDAY.atTime(7, 0), MONDAY.atTime(10, 0)))));
        assertEquals(MultiSlotPlanner.ExternalVerdict.NEUTRAL,
                MultiSlotPlanner.externalVerdict(external.get("user-1"),
                        MONDAY.atTime(7, 0), MONDAY.atTime(8, 0)),
                "AVOID never excludes");

        List<PlannedSlot> picks = plan(external, 1);
        assertEquals(MONDAY.atTime(13, 0), picks.getFirst().start(),
                "the preferred-window slot must outrank the earlier avoided one");
        assertEquals(1, picks.getFirst().preferenceScore());
    }

    // ------------------------------------------------------------------
    // Covered range + lifecycle filters (resolver side)
    // ------------------------------------------------------------------

    @Test
    void constraints_areClippedToTheCoveredRange() {
        // A busy claim running Mon–Wed on evidence covering only Tuesday
        // applies to Tuesday alone.
        Map<String, List<ExternalConstraint>> external = resolve(
                evidence(EvidenceConfirmationStatus.CONFIRMED, TUESDAY, TUESDAY, null),
                constraint(AvailabilityConstraintType.BUSY,
                        MONDAY.atTime(0, 0), WEDNESDAY.atTime(23, 59)));
        List<ExternalConstraint> constraints = external.get("user-1");
        assertEquals(1, constraints.size());
        assertEquals(TUESDAY.atStartOfDay(), constraints.getFirst().start());
        assertEquals(WEDNESDAY.atStartOfDay(), constraints.getFirst().end());
    }

    @Test
    void expiredEvidence_isIgnored() {
        // Confirmed but past its covered period: no constraints reach
        // the planner (spec §23).
        RecruitmentAvailabilityEvidence expired = evidence(
                EvidenceConfirmationStatus.CONFIRMED, MONDAY.minusDays(7),
                MONDAY.minusDays(5), MONDAY.minusDays(5).atTime(23, 59, 59));
        Map<String, List<ExternalConstraint>> external = resolveMany(Map.of(expired,
                List.of(row(expired, AvailabilityConstraintType.BUSY,
                        MONDAY.minusDays(6).atTime(9, 0), MONDAY.minusDays(6).atTime(12, 0)))));
        assertTrue(external.isEmpty());
    }

    @Test
    void unconfirmedEvidence_neverReachesTheEngine() {
        // D9: PENDING/CANCELLED/SUPERSEDED/REJECTED are all invisible.
        for (EvidenceConfirmationStatus status : List.of(
                EvidenceConfirmationStatus.PENDING, EvidenceConfirmationStatus.CANCELLED,
                EvidenceConfirmationStatus.SUPERSEDED, EvidenceConfirmationStatus.EXPIRED,
                EvidenceConfirmationStatus.REJECTED)) {
            Map<String, List<ExternalConstraint>> external = resolve(
                    evidence(status, MONDAY, MONDAY, null),
                    constraint(AvailabilityConstraintType.BUSY,
                            MONDAY.atTime(7, 0), MONDAY.atTime(19, 0)));
            assertTrue(external.isEmpty(), status + " must be ignored");
        }
    }

    // ------------------------------------------------------------------
    // The finalization staleness prompt (spec §23)
    // ------------------------------------------------------------------

    @Test
    void staleGating_flagsExpiredAvailableOnlyCoveringTheSlotDate() {
        RecruitmentAvailabilityEvidence stale = evidence(
                EvidenceConfirmationStatus.EXPIRED, MONDAY, MONDAY, MONDAY.atTime(0, 0));
        stale.setConfirmedAt(MONDAY.minusDays(3).atTime(12, 0));
        Map<RecruitmentAvailabilityEvidence, List<RecruitmentAvailabilityConstraint>> rows =
                Map.of(stale, List.of(row(stale, AvailabilityConstraintType.AVAILABLE_ONLY,
                        MONDAY.atTime(13, 0), MONDAY.atTime(15, 0))));
        List<String> users = AvailabilityConstraintResolver.staleGatingUserUuids(
                List.copyOf(rows.keySet()), byUuid(rows),
                MONDAY.atTime(13, 0), MONDAY.atTime(9, 0));
        assertEquals(List.of("user-1"), users);
    }

    @Test
    void staleGating_ignoresBusyOnlyAndNeverConfirmedEvidence() {
        // A stale BUSY claim cannot have gated the slot positively; a
        // never-confirmed row was never input at all.
        RecruitmentAvailabilityEvidence staleBusy = evidence(
                EvidenceConfirmationStatus.EXPIRED, MONDAY, MONDAY, MONDAY.atTime(0, 0));
        staleBusy.setConfirmedAt(MONDAY.minusDays(3).atTime(12, 0));
        RecruitmentAvailabilityEvidence neverConfirmed = evidence(
                EvidenceConfirmationStatus.EXPIRED, MONDAY, MONDAY, MONDAY.atTime(0, 0));
        Map<RecruitmentAvailabilityEvidence, List<RecruitmentAvailabilityConstraint>> rows =
                Map.of(staleBusy, List.of(row(staleBusy, AvailabilityConstraintType.BUSY,
                                MONDAY.atTime(9, 0), MONDAY.atTime(11, 0))),
                        neverConfirmed, List.of(row(neverConfirmed,
                                AvailabilityConstraintType.AVAILABLE_ONLY,
                                MONDAY.atTime(13, 0), MONDAY.atTime(15, 0))));
        List<String> users = AvailabilityConstraintResolver.staleGatingUserUuids(
                List.copyOf(rows.keySet()), byUuid(rows),
                MONDAY.atTime(13, 0), MONDAY.atTime(9, 0));
        assertTrue(users.isEmpty());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int evidenceSeq = 0;

    private static RecruitmentAvailabilityEvidence evidence(
            EvidenceConfirmationStatus status, LocalDate coveredFrom,
            LocalDate coveredTo, LocalDateTime expiresAt) {
        RecruitmentAvailabilityEvidence evidence = new RecruitmentAvailabilityEvidence();
        evidence.setUuid("evidence-" + (++evidenceSeq));
        evidence.setRequestUuid("request-1");
        evidence.setUserUuid("user-1");
        evidence.setIntent("PROVIDE_AVAILABILITY");
        evidence.setConfirmationStatus(status);
        evidence.setCoveredFrom(coveredFrom);
        evidence.setCoveredTo(coveredTo);
        evidence.setExpiresAt(expiresAt);
        return evidence;
    }

    private static RecruitmentAvailabilityConstraint row(
            RecruitmentAvailabilityEvidence evidence, AvailabilityConstraintType type,
            LocalDateTime start, LocalDateTime end) {
        RecruitmentAvailabilityConstraint constraint = new RecruitmentAvailabilityConstraint();
        constraint.setUuid("constraint-" + evidence.getUuid() + "-" + start);
        constraint.setEvidenceUuid(evidence.getUuid());
        constraint.setType(type);
        constraint.setStartAt(start);
        constraint.setEndAt(end);
        return constraint;
    }

    private static Map<String, List<ExternalConstraint>> resolve(
            RecruitmentAvailabilityEvidence evidence,
            RecruitmentAvailabilityConstraint constraint) {
        constraint.setEvidenceUuid(evidence.getUuid());
        return resolveMany(Map.of(evidence, List.of(constraint)));
    }

    private static RecruitmentAvailabilityConstraint constraint(
            AvailabilityConstraintType type, LocalDateTime start, LocalDateTime end) {
        RecruitmentAvailabilityConstraint constraint = new RecruitmentAvailabilityConstraint();
        constraint.setUuid("constraint-" + start);
        constraint.setType(type);
        constraint.setStartAt(start);
        constraint.setEndAt(end);
        return constraint;
    }

    private static Map<String, List<ExternalConstraint>> resolveMany(
            Map<RecruitmentAvailabilityEvidence, List<RecruitmentAvailabilityConstraint>> rows) {
        return AvailabilityConstraintResolver.resolve(
                List.copyOf(rows.keySet()), byUuid(rows), NOW);
    }

    private static Map<String, List<RecruitmentAvailabilityConstraint>> byUuid(
            Map<RecruitmentAvailabilityEvidence, List<RecruitmentAvailabilityConstraint>> rows) {
        Map<String, List<RecruitmentAvailabilityConstraint>> byUuid = new java.util.HashMap<>();
        rows.forEach((evidence, constraints) -> byUuid.put(evidence.getUuid(), constraints));
        return byUuid;
    }

    /** One-person plan over Monday, O365 all-free, with the given evidence. */
    private static List<PlannedSlot> plan(
            Map<String, List<ExternalConstraint>> byUser, int requested) {
        Map<String, List<ExternalConstraint>> byMailbox =
                byUser.containsKey("user-1") ? Map.of(A, byUser.get("user-1")) : Map.of();
        return MultiSlotPlanner.plan(new PlanRequest(
                MONDAY, MONDAY, null, null, 60, requested, 0, false, false, 2,
                List.of(A), List.of(), List.of(),
                Map.of(A, new MailboxWindowSchedule("0".repeat(1500), null)),
                EARLY, List.of(), List.of(), List.of(), byMailbox));
    }
}
