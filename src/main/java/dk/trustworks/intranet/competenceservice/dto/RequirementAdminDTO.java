package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Targeting;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;

import java.util.List;

/**
 * One row of the authoring topic list (spec §6.3).
 *
 * <p>The requirement plus everything an author needs to decide what to do about it without
 * opening it: what is live, what is sitting in draft, and how many authoring markers are still
 * unresolved. The list is the working surface for content, so the four version slots are
 * separate fields rather than a history array — "COURSE has a draft, TEST does not" is the
 * thing being scanned for, and reducing an array to that on the client is a rule that would
 * then exist in two places.
 *
 * <p>The three targeting arrays travel as parsed lists, with {@code null} preserved as absent
 * (§5.2) — the same asymmetry {@link RequirementUpsertRequest} documents. The editor round-trips
 * these fields, so flattening a null into {@code []} here would let a save-without-edit empty
 * an audience.
 *
 * <p>Payloads never appear: see {@link VersionSummaryDTO}.
 *
 * @param cadenceDaysOverride the raw override, {@code null} when unset — the editor has to show
 *                            an empty field, not the global value pretending to be an override
 * @param effectiveCadenceDays what is actually in force, so the list can say "365 (global)"
 *                            without re-implementing the fallback
 * @param activeCourseVersion  the live microcourse, or {@code null} — a requirement missing
 *                            either ACTIVE track is invisible to employees (§5.1), so a null
 *                            here is the explanation for a krav nobody can see
 * @param draftCourseVersion  the single open COURSE draft, or {@code null}
 * @param activeTestVersion   the live test, or {@code null}
 * @param draftTestVersion    the single open TEST draft, or {@code null}
 * @param unresolvedPlaceholderCount markers in the draft when there is one, otherwise in the
 *                            ACTIVE course. Non-zero on an ACTIVE row means placeholder text is
 *                            live in front of employees right now, which is the strongest
 *                            reason this number is in the list rather than only in the publish
 *                            dialog.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequirementAdminDTO(String uuid,
                                  String compId,
                                  String kref,
                                  String name,
                                  String description,
                                  List<String> targetPracticeUuids,
                                  List<String> targetTeams,
                                  List<String> targetUseruuids,
                                  Integer cadenceDaysOverride,
                                  int effectiveCadenceDays,
                                  int sortOrder,
                                  boolean active,
                                  VersionSummaryDTO activeCourseVersion,
                                  VersionSummaryDTO draftCourseVersion,
                                  VersionSummaryDTO activeTestVersion,
                                  VersionSummaryDTO draftTestVersion,
                                  int unresolvedPlaceholderCount) {

    /**
     * @param targeting            from {@code CompetenceRequirementService.targetingOf} —
     *                             parsed once by the service, never re-parsed here
     * @param versions             the four slots, any of which may be {@code null}
     * @param effectiveCadenceDays from {@code CompetenceSettingsService.effectiveCadenceDays}
     */
    public static RequirementAdminDTO of(CompetenceRequirement requirement,
                                         Targeting targeting,
                                         CompetenceContentVersion activeCourse,
                                         CompetenceContentVersion draftCourse,
                                         CompetenceContentVersion activeTest,
                                         CompetenceContentVersion draftTest,
                                         int effectiveCadenceDays,
                                         int unresolvedPlaceholderCount) {
        return new RequirementAdminDTO(
                requirement.getUuid(),
                requirement.getCompId(),
                requirement.getKref(),
                requirement.getName(),
                requirement.getDescription(),
                targeting == null ? null : targeting.practiceUuids(),
                targeting == null ? null : targeting.teamUuids(),
                targeting == null ? null : targeting.userUuids(),
                requirement.getCadenceDaysOverride(),
                effectiveCadenceDays,
                requirement.getSortOrder(),
                requirement.isActive(),
                VersionSummaryDTO.ofNullable(activeCourse),
                VersionSummaryDTO.ofNullable(draftCourse),
                VersionSummaryDTO.ofNullable(activeTest),
                VersionSummaryDTO.ofNullable(draftTest),
                unresolvedPlaceholderCount);
    }
}
