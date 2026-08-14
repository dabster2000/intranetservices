package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.services.MultiSlotPlanner.ExternalConstraint;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Turns confirmed evidence rows into the planner's per-interviewer
 * constraint lists (plan §12.5, spec §12): the covered-range and
 * expiry rules live HERE, so {@link MultiSlotPlanner} stays a pure
 * interval machine:
 * <ul>
 *   <li>Only CONFIRMED evidence participates (D9) — and only until its
 *       {@code expiresAt} (spec §23: expired evidence is ignored).</li>
 *   <li>Constraints are CLIPPED to their evidence's covered range —
 *       a claim never applies outside the dates it was made about
 *       (spec §11.5's visible-range rule, applied to text too).</li>
 *   <li>AVAILABLE_ONLY intervals carry their covered range as the
 *       restricted-day window and are merged per interviewer, so
 *       adjacent windows ("9–12 og 12–15") behave as one.</li>
 * </ul>
 * Pure and CDI-free — the precedence matrix test drives it together
 * with the planner.
 */
public final class AvailabilityConstraintResolver {

    private AvailabilityConstraintResolver() {
    }

    /**
     * Resolve per-user planner constraints from evidence + constraint
     * rows. {@code now} decides expiry (a parameter for determinism).
     */
    public static Map<String, List<ExternalConstraint>> resolve(
            List<RecruitmentAvailabilityEvidence> evidenceRows,
            Map<String, List<RecruitmentAvailabilityConstraint>> constraintsByEvidence,
            LocalDateTime now) {
        Map<String, List<ExternalConstraint>> byUser = new HashMap<>();
        for (RecruitmentAvailabilityEvidence evidence : evidenceRows) {
            if (!evidence.getConfirmationStatus().isSchedulingInput()) {
                continue;
            }
            if (evidence.getExpiresAt() != null && !evidence.getExpiresAt().isAfter(now)) {
                continue; // spec §23 — expired, ignored
            }
            List<RecruitmentAvailabilityConstraint> rows =
                    constraintsByEvidence.getOrDefault(evidence.getUuid(), List.of());
            for (RecruitmentAvailabilityConstraint row : rows) {
                ExternalConstraint clipped = clip(evidence, row);
                if (clipped != null) {
                    byUser.computeIfAbsent(evidence.getUserUuid(), k -> new ArrayList<>())
                            .add(clipped);
                }
            }
        }
        byUser.replaceAll((user, constraints) -> mergeAvailableOnly(constraints));
        return byUser;
    }

    /** Clip one constraint to its evidence's covered range; null = empty. */
    static ExternalConstraint clip(RecruitmentAvailabilityEvidence evidence,
                                   RecruitmentAvailabilityConstraint row) {
        LocalDateTime start = row.getStartAt();
        LocalDateTime end = row.getEndAt();
        LocalDate coveredFrom = evidence.getCoveredFrom();
        LocalDate coveredTo = evidence.getCoveredTo();
        if (coveredFrom != null && coveredTo != null) {
            LocalDateTime coverStart = coveredFrom.atStartOfDay();
            LocalDateTime coverEnd = coveredTo.plusDays(1).atStartOfDay();
            if (start.isBefore(coverStart)) {
                start = coverStart;
            }
            if (end.isAfter(coverEnd)) {
                end = coverEnd;
            }
            if (!start.isBefore(end)) {
                return null; // entirely outside the covered range
            }
        }
        boolean availableOnly = row.getType() == AvailabilityConstraintType.AVAILABLE_ONLY;
        LocalDate restrictedFrom = null;
        LocalDate restrictedTo = null;
        if (availableOnly) {
            // The days this exclusivity claim governs: the covered range,
            // or — when the evidence had none — the constraint's own days.
            restrictedFrom = coveredFrom != null ? coveredFrom : start.toLocalDate();
            restrictedTo = coveredTo != null ? coveredTo : end.toLocalDate();
        }
        return new ExternalConstraint(row.getType(), start, end, restrictedFrom, restrictedTo);
    }

    /**
     * Merge overlapping/adjacent AVAILABLE_ONLY intervals sharing one
     * restricted-day window, so "9–12 og 12–15" admits an 11–13 slot.
     * Other types pass through untouched.
     */
    static List<ExternalConstraint> mergeAvailableOnly(List<ExternalConstraint> constraints) {
        List<ExternalConstraint> result = new ArrayList<>();
        List<ExternalConstraint> availableOnly = new ArrayList<>();
        for (ExternalConstraint constraint : constraints) {
            if (constraint.type() == AvailabilityConstraintType.AVAILABLE_ONLY) {
                availableOnly.add(constraint);
            } else {
                result.add(constraint);
            }
        }
        // Group by restricted window; merge each group's sorted intervals.
        LinkedHashSet<List<LocalDate>> windows = new LinkedHashSet<>();
        for (ExternalConstraint constraint : availableOnly) {
            windows.add(List.of(constraint.restrictedFrom(), constraint.restrictedTo()));
        }
        for (List<LocalDate> window : windows) {
            List<ExternalConstraint> group = availableOnly.stream()
                    .filter(c -> c.restrictedFrom().equals(window.get(0))
                            && c.restrictedTo().equals(window.get(1)))
                    .sorted(Comparator.comparing(ExternalConstraint::start))
                    .toList();
            ExternalConstraint current = null;
            for (ExternalConstraint next : group) {
                if (current == null) {
                    current = next;
                } else if (!next.start().isAfter(current.end())) {
                    if (next.end().isAfter(current.end())) {
                        current = new ExternalConstraint(current.type(), current.start(),
                                next.end(), current.restrictedFrom(), current.restrictedTo());
                    }
                } else {
                    result.add(current);
                    current = next;
                }
            }
            if (current != null) {
                result.add(current);
            }
        }
        return result;
    }

    /**
     * The finalization staleness check (spec §23, plan §12.5): which
     * interviewers' EXPIRED-but-once-confirmed evidence materially gated
     * the selected slot — material = an AVAILABLE_ONLY claim whose
     * restricted days cover the slot's date (the slot existed because of
     * that exclusivity claim). The prompt this feeds is deliberately
     * NON-blocking: every required interviewer explicitly approved this
     * slot with a button, and a fresh direct approval outranks a stale
     * generic claim — the live O365 recheck guards actual conflicts.
     */
    public static List<String> staleGatingUserUuids(
            List<RecruitmentAvailabilityEvidence> evidenceRows,
            Map<String, List<RecruitmentAvailabilityConstraint>> constraintsByEvidence,
            LocalDateTime slotStart, LocalDateTime now) {
        LinkedHashSet<String> users = new LinkedHashSet<>();
        LocalDate slotDate = slotStart.toLocalDate();
        for (RecruitmentAvailabilityEvidence evidence : evidenceRows) {
            boolean wasConfirmed = evidence.getConfirmedAt() != null;
            boolean expired = evidence.getConfirmationStatus() == EvidenceConfirmationStatus.EXPIRED
                    || (evidence.getConfirmationStatus() == EvidenceConfirmationStatus.CONFIRMED
                            && evidence.getExpiresAt() != null
                            && !evidence.getExpiresAt().isAfter(now));
            if (!wasConfirmed || !expired) {
                continue;
            }
            for (RecruitmentAvailabilityConstraint row :
                    constraintsByEvidence.getOrDefault(evidence.getUuid(), List.of())) {
                if (row.getType() != AvailabilityConstraintType.AVAILABLE_ONLY) {
                    continue;
                }
                LocalDate from = evidence.getCoveredFrom() != null
                        ? evidence.getCoveredFrom() : row.getStartAt().toLocalDate();
                LocalDate to = evidence.getCoveredTo() != null
                        ? evidence.getCoveredTo() : row.getEndAt().toLocalDate();
                if (!slotDate.isBefore(from) && !slotDate.isAfter(to)) {
                    users.add(evidence.getUserUuid());
                    break;
                }
            }
        }
        return List.copyOf(users);
    }
}
