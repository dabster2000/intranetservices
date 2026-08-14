package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.competenceservice.content.CompetenceContent;

import java.util.List;

/**
 * One microcourse screen, compiled for the player (spec §6.1, §9.2).
 *
 * <p>The only thing this adds to the stored screen is {@code eyebrow}, derived by
 * {@link dk.trustworks.intranet.competenceservice.domain.CompetenceEyebrow} and never stored:
 * a persisted "Afsnit 4 af 10" is wrong the moment an author inserts a section, and wrong in a
 * way nobody notices until an auditor counts.
 *
 * <p><strong>{@code blocks} reuses {@code CompetenceContent.Block} on purpose, and that is a
 * judgement about this one type — not a general licence to serve payload records as DTOs.</strong>
 * A block carries no correctness flag and no authoring metadata, so there is nothing to strip
 * and a parallel {@code BlockDTO} would be a copy that has to be kept in step with seven block
 * types for no gain. The test payload is the opposite case: {@code Option} carries
 * {@code correct}, so the learner surface uses a separate {@link LearnerOption} that has no
 * such field at all (§6.4, §10.2). The rule is per type — "does this record carry anything the
 * reader may not see" — and it must be asked again for every payload type somebody is tempted
 * to pass straight through.
 *
 * @param role    one of {@code intro}, {@code content}, {@code video}, {@code summary}
 * @param title   screen title; validation requires it, so it is non-null in published content
 * @param lede    optional standfirst under the title
 * @param eyebrow the derived label above the title, positionally aligned with the payload's
 *                screens by {@link CourseContentDTO#of}
 * @param blocks  the authored blocks, in order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourseScreenDTO(String role,
                              String title,
                              String lede,
                              String eyebrow,
                              List<CompetenceContent.Block> blocks) {

    public CourseScreenDTO {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /** @param eyebrow the derived label for this screen's position, may be {@code null} */
    public static CourseScreenDTO of(CompetenceContent.Screen screen, String eyebrow) {
        return new CourseScreenDTO(
                screen.role(), screen.title(), screen.lede(), eyebrow, screen.blocks());
    }
}
