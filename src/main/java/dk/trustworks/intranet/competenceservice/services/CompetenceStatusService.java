package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.domain.CompetenceStatus;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator.AttemptFact;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator.CompletionFact;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttemptDecision;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceCourseCompletion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the facts the pure {@link CompetenceStatusCalculator} needs, in batch.
 *
 * <p>The matrix is O(users × requirements). With ~60 people and a handful of requirements
 * that is small, but resolving it per cell would still mean thousands of queries, so every
 * read here is one query per <em>table</em> for the whole population — never per cell.
 *
 * <p>All the decision logic lives in the calculator; this class only fetches and groups.
 * That split is what keeps §5.3–§5.5 testable without a database.
 */
@ApplicationScoped
public class CompetenceStatusService {

    @Inject
    CompetenceSettingsService settingsService;

    /**
     * Everything needed to colour a whole matrix, loaded in four queries.
     *
     * @param activeCourseLabels requirementUuid → active COURSE version label (absent = unpublished)
     * @param activeTestLabels   requirementUuid → active TEST version label
     * @param completions        useruuid → requirementUuid → completions
     * @param attempts           useruuid → requirementUuid → submitted attempts
     */
    public record Snapshot(Map<String, String> activeCourseLabels,
                           Map<String, String> activeTestLabels,
                           Map<String, Map<String, List<CompletionFact>>> completions,
                           Map<String, Map<String, List<AttemptFact>>> attempts) {

        public List<CompletionFact> completionsFor(String useruuid, String requirementUuid) {
            return completions.getOrDefault(useruuid, Map.of())
                    .getOrDefault(requirementUuid, List.of());
        }

        public List<AttemptFact> attemptsFor(String useruuid, String requirementUuid) {
            return attempts.getOrDefault(useruuid, Map.of())
                    .getOrDefault(requirementUuid, List.of());
        }
    }

    /** Load the whole picture for a set of people. */
    public Snapshot snapshot(List<String> useruuids) {
        Map<String, String> courseLabels = new HashMap<>();
        Map<String, String> testLabels = new HashMap<>();
        for (CompetenceContentVersion version : CompetenceContentVersion.listAllActive()) {
            if (version.getContentKind() == ContentKind.COURSE) {
                courseLabels.put(version.getRequirementUuid(), version.getVersionLabel());
            } else {
                testLabels.put(version.getRequirementUuid(), version.getVersionLabel());
            }
        }

        Map<String, Map<String, List<CompletionFact>>> completions = new HashMap<>();
        for (CompetenceCourseCompletion row : CompetenceCourseCompletion.listForUsers(useruuids)) {
            completions
                    .computeIfAbsent(row.getUseruuid(), k -> new HashMap<>())
                    .computeIfAbsent(row.getRequirementUuid(), k -> new ArrayList<>())
                    .add(new CompletionFact(row.getVersionLabel(), row.getCompletedAt()));
        }

        List<CompetenceAttempt> submitted = CompetenceAttempt.listSubmittedForUsers(useruuids);
        Map<String, CompetenceAttemptDecision> latestDecisions =
                latestDecisionsFor(submitted.stream().map(CompetenceAttempt::getUuid).toList());

        Map<String, Map<String, List<AttemptFact>>> attempts = new HashMap<>();
        for (CompetenceAttempt attempt : submitted) {
            CompetenceAttemptDecision decision = latestDecisions.get(attempt.getUuid());
            attempts
                    .computeIfAbsent(attempt.getUseruuid(), k -> new HashMap<>())
                    .computeIfAbsent(attempt.getRequirementUuid(), k -> new ArrayList<>())
                    .add(new AttemptFact(
                            attempt.getVersionLabel(),
                            attempt.getSubmittedAt(),
                            attempt.isPassed(),
                            decision == null ? null : decision.getDecision()));
        }
        return new Snapshot(courseLabels, testLabels, completions, attempts);
    }

    /**
     * attemptUuid → deciding row. The query returns newest first, so the first row seen
     * for an attempt is the deciding one and later ones are superseded history.
     */
    public Map<String, CompetenceAttemptDecision> latestDecisionsFor(List<String> attemptUuids) {
        Map<String, CompetenceAttemptDecision> latest = new HashMap<>();
        for (CompetenceAttemptDecision decision : CompetenceAttemptDecision.listForAttempts(attemptUuids)) {
            latest.putIfAbsent(decision.getAttemptUuid(), decision);
        }
        return latest;
    }

    // -----------------------------------------------------------------------

    public CompetenceStatus.Course courseStatus(CompetenceRequirement requirement,
                                                Snapshot snapshot,
                                                String useruuid,
                                                LocalDateTime now) {
        return CompetenceStatusCalculator.courseStatus(
                snapshot.activeCourseLabels().get(requirement.getUuid()),
                snapshot.completionsFor(useruuid, requirement.getUuid()),
                settingsService.effectiveCadenceDays(requirement),
                now);
    }

    public CompetenceStatus.Test testStatus(CompetenceRequirement requirement,
                                            Snapshot snapshot,
                                            String useruuid,
                                            LocalDateTime now) {
        return CompetenceStatusCalculator.testStatus(
                snapshot.activeTestLabels().get(requirement.getUuid()),
                snapshot.attemptsFor(useruuid, requirement.getUuid()),
                settingsService.effectiveCadenceDays(requirement),
                now);
    }

    /**
     * Whether one person may start a test right now. Loads only that person's facts —
     * the gate is re-evaluated on every start, so it must stay cheap.
     */
    public boolean courseGateOpen(CompetenceRequirement requirement, String useruuid, LocalDateTime now) {
        Snapshot snapshot = snapshot(List.of(useruuid));
        return CompetenceStatusCalculator.courseGateOpen(
                courseStatus(requirement, snapshot, useruuid, now));
    }
}
