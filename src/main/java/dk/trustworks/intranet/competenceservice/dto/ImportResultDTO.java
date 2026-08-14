package dk.trustworks.intranet.competenceservice.dto;

import java.util.List;

/**
 * What a content import did (spec §6.3, §9.6).
 *
 * <p>Shaped after {@code CompetenceContentService.ImportSummary} and kept separate so the
 * service's internal result can change without moving a wire contract; the resource maps the
 * four fields.
 *
 * <p>The counts are the confirmation an author needs, because the import deliberately does
 * <em>not</em> do the obvious thing: it writes DRAFTs only and never activates anything (rule
 * C-2 — a draft must never reach an employee). So "4 topics, 8 drafts written" has to be
 * readable as "and nothing is live yet", which is why {@code draftsWritten} is named for what
 * it wrote rather than for what was imported. Somebody still has to open each draft and press
 * publish.
 *
 * <p>Nothing is written at all when any topic fails validation — a partially imported
 * four-topic file is a state nobody can reason about — so these numbers always describe a
 * complete file.
 *
 * @param topics              topics read from the file
 * @param requirementsCreated requirements the file brought into existence. Non-zero is worth
 *                            noticing: a typo in a {@code compId} creates a new krav instead of
 *                            updating the intended one, and this count is where that shows.
 * @param draftsWritten       COURSE + TEST drafts written across all topics
 * @param compIds             the topics touched, in file order
 */
public record ImportResultDTO(int topics,
                              int requirementsCreated,
                              int draftsWritten,
                              List<String> compIds) {

    public ImportResultDTO {
        compIds = compIds == null ? List.of() : List.copyOf(compIds);
    }
}
