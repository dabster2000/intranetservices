package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceSourceType;
import dk.trustworks.intranet.recruitmentservice.services.MultiSlotPlanner.ExternalConstraint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner decision 2026-08-18: an AVAILABLE_ONLY window read out of a calendar
 * IMAGE restricts the day but must NOT override the O365 calendar the way a
 * typed statement does (F1a).
 * <p>
 * The reasoning: F1a exists because a person who says "I can do Wednesday at 14"
 * is implicitly promising to move whatever else is in their calendar. A picture
 * makes no such promise — it is a machine reading of a grid, and the 2026-08-18
 * misread showed how wrong that reading can be. So an image narrows the
 * candidate slots, and the real calendar still gets the last word.
 * <p>
 * A BUSY claim is unchanged and still absolute, from any source: busy always
 * wins (§27 scenario 4).
 */
class AvailabilityImageOverrideTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final LocalDateTime NOW = MONDAY.minusDays(1).atTime(6, 0);
    private static final LocalDateTime SLOT_START = MONDAY.atTime(14, 0);
    private static final LocalDateTime SLOT_END = MONDAY.atTime(15, 0);

    private int seq;

    // ---- The resolver marks the source ------------------------------------

    @Test
    void imageEvidenceProducesNonOverridingWindows() {
        List<ExternalConstraint> resolved = resolve(EvidenceSourceType.IMAGE,
                AvailabilityConstraintType.AVAILABLE_ONLY,
                MONDAY.atTime(13, 0), MONDAY.atTime(16, 0));
        assertEquals(1, resolved.size());
        assertFalse(resolved.getFirst().overridesCalendar(),
                "a window read out of a picture may not beat the real calendar");
    }

    @Test
    void typedEvidenceKeepsTheF1aOverride() {
        for (EvidenceSourceType source : List.of(EvidenceSourceType.TEXT,
                EvidenceSourceType.CORRECTION, EvidenceSourceType.RECRUITER,
                EvidenceSourceType.BUTTON)) {
            List<ExternalConstraint> resolved = resolve(source,
                    AvailabilityConstraintType.AVAILABLE_ONLY,
                    MONDAY.atTime(13, 0), MONDAY.atTime(16, 0));
            assertTrue(resolved.getFirst().overridesCalendar(),
                    source + " is a human statement and keeps F1a");
        }
    }

    // ---- The planner honours it ------------------------------------------

    @Test
    void imageWindow_fitsButDefersToTheCalendar() {
        MultiSlotPlanner.ExternalVerdict verdict = MultiSlotPlanner.externalVerdict(
                resolve(EvidenceSourceType.IMAGE, AvailabilityConstraintType.AVAILABLE_ONLY,
                        MONDAY.atTime(13, 0), MONDAY.atTime(16, 0)),
                SLOT_START, SLOT_END);
        assertEquals(MultiSlotPlanner.ExternalVerdict.NEUTRAL, verdict,
                "NEUTRAL means: allowed by the statement, now go ask O365");
    }

    @Test
    void typedWindow_overridesTheCalendar() {
        MultiSlotPlanner.ExternalVerdict verdict = MultiSlotPlanner.externalVerdict(
                resolve(EvidenceSourceType.TEXT, AvailabilityConstraintType.AVAILABLE_ONLY,
                        MONDAY.atTime(13, 0), MONDAY.atTime(16, 0)),
                SLOT_START, SLOT_END);
        assertEquals(MultiSlotPlanner.ExternalVerdict.AVAILABLE_OVERRIDE, verdict);
    }

    @Test
    void imageWindow_stillBlocksSlotsOutsideIt() {
        // The restriction half is unchanged — only the override half is removed.
        MultiSlotPlanner.ExternalVerdict verdict = MultiSlotPlanner.externalVerdict(
                resolve(EvidenceSourceType.IMAGE, AvailabilityConstraintType.AVAILABLE_ONLY,
                        MONDAY.atTime(9, 0), MONDAY.atTime(11, 0)),
                SLOT_START, SLOT_END);
        assertEquals(MultiSlotPlanner.ExternalVerdict.BLOCKED, verdict,
                "a 14:00 slot outside a 09:00-11:00 exclusive window is still out");
    }

    @Test
    void imageBusyIsStillAbsolute() {
        MultiSlotPlanner.ExternalVerdict verdict = MultiSlotPlanner.externalVerdict(
                resolve(EvidenceSourceType.IMAGE, AvailabilityConstraintType.BUSY,
                        MONDAY.atTime(13, 30), MONDAY.atTime(14, 30)),
                SLOT_START, SLOT_END);
        assertEquals(MultiSlotPlanner.ExternalVerdict.BLOCKED, verdict,
                "busy always wins, whatever the source");
    }

    @Test
    void aTypedWindowIsNotWeakenedByMergingWithAnImageWindow() {
        // The subtle one: mergeAvailableOnly groups by restricted window. If it
        // ignored the override flag, an image window could absorb a typed one
        // and silently hand the picture an authority the owner denied it — or
        // strip the human's F1a override. They must stay separate.
        RecruitmentAvailabilityEvidence typed = evidence(EvidenceSourceType.TEXT);
        RecruitmentAvailabilityEvidence image = evidence(EvidenceSourceType.IMAGE);
        Map<RecruitmentAvailabilityEvidence, List<RecruitmentAvailabilityConstraint>> rows =
                new HashMap<>();
        rows.put(typed, List.of(row(typed, AvailabilityConstraintType.AVAILABLE_ONLY,
                MONDAY.atTime(9, 0), MONDAY.atTime(12, 0))));
        rows.put(image, List.of(row(image, AvailabilityConstraintType.AVAILABLE_ONLY,
                MONDAY.atTime(12, 0), MONDAY.atTime(15, 0))));

        List<ExternalConstraint> resolved =
                AvailabilityConstraintResolver.resolve(List.copyOf(rows.keySet()),
                        byUuid(rows), NOW).get("user-1");

        assertEquals(2, resolved.size(), "adjacent windows from different sources do not merge");
        assertEquals(1, resolved.stream().filter(ExternalConstraint::overridesCalendar).count());
        assertEquals(1, resolved.stream().filter(c -> !c.overridesCalendar()).count());
    }

    // ---- helpers ----------------------------------------------------------

    private List<ExternalConstraint> resolve(EvidenceSourceType source,
                                             AvailabilityConstraintType type,
                                             LocalDateTime start, LocalDateTime end) {
        RecruitmentAvailabilityEvidence evidence = evidence(source);
        Map<RecruitmentAvailabilityEvidence, List<RecruitmentAvailabilityConstraint>> rows =
                Map.of(evidence, List.of(row(evidence, type, start, end)));
        return AvailabilityConstraintResolver.resolve(
                List.copyOf(rows.keySet()), byUuid(rows), NOW).get("user-1");
    }

    private RecruitmentAvailabilityEvidence evidence(EvidenceSourceType source) {
        RecruitmentAvailabilityEvidence evidence = new RecruitmentAvailabilityEvidence();
        evidence.setUuid("evidence-" + (++seq));
        evidence.setRequestUuid("request-1");
        evidence.setUserUuid("user-1");
        evidence.setIntent("PROVIDE_AVAILABILITY");
        evidence.setSourceType(source);
        evidence.setConfirmationStatus(EvidenceConfirmationStatus.CONFIRMED);
        evidence.setCoveredFrom(MONDAY);
        evidence.setCoveredTo(MONDAY);
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

    private static Map<String, List<RecruitmentAvailabilityConstraint>> byUuid(
            Map<RecruitmentAvailabilityEvidence, List<RecruitmentAvailabilityConstraint>> rows) {
        Map<String, List<RecruitmentAvailabilityConstraint>> byUuid = new HashMap<>();
        rows.forEach((evidence, constraints) -> byUuid.put(evidence.getUuid(), constraints));
        return byUuid;
    }
}
