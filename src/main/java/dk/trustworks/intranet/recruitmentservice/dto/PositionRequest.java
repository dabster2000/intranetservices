package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentDemandRag;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for creating or updating a {@code RecruitmentPosition}.
 * <p>
 * On create: {@code title} and {@code hiringTrack} are required;
 * track-conditional rules ({@code PRACTICE_TEAM} requires an <em>active</em>
 * {@code practiceUuid}, {@code STAFF_ROLE} requires {@code hiringOwnerUuid})
 * are enforced in {@code RecruitmentPositionService}. Omitted
 * {@code stageSet} / {@code scorecardTemplate} fall back to the track
 * defaults, snapshotted onto the position.
 * <p>
 * On update: {@code hiringTrack} must equal the stored track (immutable);
 * {@code status} may toggle OPEN ↔ ON_HOLD but never CLOSED (use the close
 * endpoint). Null optional fields clear the stored value — PUT semantics,
 * the dialog always sends the full shape.
 */
public record PositionRequest(
        @NotBlank(message = "title is required") @Size(max = 200) String title,
        @NotNull(message = "hiringTrack is required") RecruitmentHiringTrack hiringTrack,
        @Size(min = 36, max = 36) String practiceUuid,
        @Size(min = 36, max = 36) String teamUuid,
        @Size(min = 36, max = 36) String hiringOwnerUuid,
        @Size(max = 80)
        @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*",
                message = "publicSlug must be lowercase letters, digits and single hyphens, e.g. senior-consultant-pm")
        String publicSlug,
        List<String> stageSet,
        List<ScorecardAttribute> scorecardTemplate,
        RecruitmentDemandRag demandRag,
        RecruitmentPositionStatus status
) {
}
