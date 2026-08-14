package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.competenceservice.domain.CompetencePlaceholderScanner;

import java.util.List;

/**
 * What the publish confirmation dialog has to say before anybody presses the button
 * (spec §6.3, §9.4).
 *
 * <p>Publishing is the only action in the module that changes what an employee owes, for a
 * whole audience, at once. It has three failure modes that are all invisible at the moment of
 * clicking and expensive afterwards, and this DTO exists to make each of them visible first.
 *
 * <ul>
 *   <li><strong>Placeholders.</strong> The 2026-08 content ships with 28 unresolved authoring
 *       markers. Publishing them puts {@code [Udfyldes + godkendes inden ibrugtagning]} in
 *       front of an auditor, which discredits the module more thoroughly than having no module.
 *       {@code unresolvedFindings} names them so the author can act, not merely be warned.</li>
 *   <li><strong>Same label.</strong> The status rules compare version <em>labels</em> (§5.3,
 *       §5.4), so republishing under the outgoing label leaves everyone green even with
 *       force-retake on. That is a documented property, not a bug — and it is also exactly how
 *       a "mandatory annual retake" quietly becomes nothing at all. The dialog must say it.</li>
 *   <li><strong>Force-retake on a test.</strong> {@code forcedRetake = false} has no effect on
 *       a TEST: attempts are immutable, so there is nothing to carry forward and everybody
 *       re-sits regardless. The dialog says that too, because the flag reads like it applies to
 *       both tracks.</li>
 * </ul>
 *
 * <p>Read-only. Computing this preview must not touch the draft, so a dialog that is opened
 * and cancelled leaves nothing behind.
 *
 * @param fromVersion       the outgoing ACTIVE label, or {@code null} for a first publish
 * @param toVersion         the DRAFT's label — what everyone will be measured against
 * @param forcedRetake      the flag the dialog is about to send
 * @param unresolvedCount   how many markers remain; {@code 0} for a TEST, which carries none
 * @param unresolvedFindings the markers themselves — screen, block type, TW ref and an excerpt.
 *                          Reuses the scanner's own record: it is already the shape an author
 *                          needs, it carries nothing an author holding {@code competence:write}
 *                          may not see, and a parallel DTO would be a copy to keep in step.
 * @param sameLabel         whether {@code toVersion} equals {@code fromVersion}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublishPreviewDTO(String fromVersion,
                                String toVersion,
                                boolean forcedRetake,
                                int unresolvedCount,
                                List<CompetencePlaceholderScanner.Finding> unresolvedFindings,
                                boolean sameLabel) {

    public PublishPreviewDTO {
        unresolvedFindings = unresolvedFindings == null ? List.of() : List.copyOf(unresolvedFindings);
    }
}
