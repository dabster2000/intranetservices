package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import dk.trustworks.intranet.competenceservice.model.ContentStatus;

import java.time.LocalDateTime;

/**
 * One content version, without its payload (spec §6.3,
 * {@code GET /admin/requirements/{uuid}/versions}).
 *
 * <p>Dropping {@code payloadJson} is the entire point of this type. A version history is a
 * list — thirteen screens of authored content per row would make it megabytes, and the history
 * view never renders a payload; it links to the editor. Serving the entity instead would also
 * put a LONGTEXT column on the wire every time somebody opened a dropdown.
 *
 * <p>{@code supersededByUuid} makes the chain walkable: an ARCHIVED row points at what replaced
 * it, so "which version was live in March" is answerable from this list alone — which is what
 * an approved attempt's frozen label has to be read against.
 *
 * @param status           DRAFT, ACTIVE or ARCHIVED; serialised as the constant name
 * @param forcedRetake     what the publish was declared to be. A {@code false} in the history
 *                         is the record that a version was published as a typo fix and did not
 *                         reset anybody's cadence — the answer to "why is this person still
 *                         green on an older reading".
 * @param publishedBy      actor uuid, null while DRAFT
 * @param supersededByUuid the version that replaced this one, null unless ARCHIVED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VersionSummaryDTO(String uuid,
                                ContentKind contentKind,
                                String versionLabel,
                                ContentStatus status,
                                boolean forcedRetake,
                                String publishedBy,
                                LocalDateTime publishedAt,
                                String supersededByUuid,
                                LocalDateTime createdAt) {

    /** @param version never {@code null}; callers pass {@code null} through themselves */
    public static VersionSummaryDTO of(CompetenceContentVersion version) {
        return new VersionSummaryDTO(
                version.getUuid(),
                version.getContentKind(),
                version.getVersionLabel(),
                version.getStatus(),
                version.isForcedRetake(),
                version.getPublishedBy(),
                version.getPublishedAt(),
                version.getSupersededByUuid(),
                version.getCreatedAt());
    }

    /** Null-tolerant variant, for the "is there a draft" slots of {@link RequirementAdminDTO}. */
    public static VersionSummaryDTO ofNullable(CompetenceContentVersion version) {
        return version == null ? null : of(version);
    }
}
