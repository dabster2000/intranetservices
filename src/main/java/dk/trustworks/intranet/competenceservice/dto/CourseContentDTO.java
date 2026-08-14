package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.competenceservice.content.CompetenceContent;
import dk.trustworks.intranet.competenceservice.domain.CompetenceEyebrow;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;

import java.util.ArrayList;
import java.util.List;

/**
 * A microcourse as the player renders it (spec §6.1,
 * {@code GET /me/requirements/{uuid}/course}).
 *
 * <p>Always the ACTIVE version — a DRAFT must never reach an employee (§6.3), so there is no
 * parameter here to ask for one.
 *
 * <p>{@code contentVersionUuid} and {@code versionLabel} both travel because completion is
 * recorded against the version that was read: the label is what the status rules compare
 * (§5.3) and the uuid is what the completion row points at. A player that posted a completion
 * without knowing which version it had shown would be recording an unfalsifiable claim.
 *
 * @param versionLabel      the ACTIVE label, e.g. {@code 1.0}
 * @param contentVersionUuid the ACTIVE version row
 * @param screens           in payload order, each carrying its derived eyebrow
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourseContentDTO(String requirementUuid,
                               String compId,
                               String kref,
                               String name,
                               String versionLabel,
                               String contentVersionUuid,
                               List<CourseScreenDTO> screens) {

    public CourseContentDTO {
        screens = screens == null ? List.of() : List.copyOf(screens);
    }

    /**
     * Compiles the stored payload for the player.
     *
     * <p>{@link CompetenceEyebrow#derive} returns one label per screen, positionally aligned
     * with {@code payload.screens()} — the two lists are zipped by index here, and by index
     * only. A lookup keyed on the screen title would collapse two screens that happen to share
     * a title, which authored content does (several topics have two "Eksempel" screens).
     *
     * <p>Defensive on length regardless: a derivation that ever returned a shorter list must
     * cost the tail its eyebrow, not throw an {@code IndexOutOfBoundsException} at the
     * employee.
     *
     * @param version the ACTIVE version row; its label and uuid travel to the player
     * @param payload the parsed payload of that version
     */
    public static CourseContentDTO of(CompetenceRequirement requirement,
                                      CompetenceContentVersion version,
                                      CompetenceContent.CoursePayload payload) {
        List<CompetenceContent.Screen> screens =
                payload == null ? List.of() : payload.screens();
        List<String> eyebrows = CompetenceEyebrow.derive(payload);

        List<CourseScreenDTO> out = new ArrayList<>(screens.size());
        for (int i = 0; i < screens.size(); i++) {
            out.add(CourseScreenDTO.of(screens.get(i), i < eyebrows.size() ? eyebrows.get(i) : null));
        }

        return new CourseContentDTO(
                requirement.getUuid(),
                requirement.getCompId(),
                requirement.getKref(),
                requirement.getName(),
                version.getVersionLabel(),
                version.getUuid(),
                out);
    }
}
