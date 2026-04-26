package dk.trustworks.intranet.recruitmentservice.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Typed projection of the {@code AiArtifact.output} JSON payload produced by the
 * CANDIDATE_SUMMARY AI generator (spec §6.3 / prompt-catalog v1
 * {@code candidate-summary-v1}).
 *
 * <p>This record is the contract the frontend deserialises against; the AI output is
 * advisory only — there is no apply-handler, so accepting/overriding does not patch the
 * Candidate aggregate (in contrast to {@link RoleBriefOutput}, which mutates
 * {@code OpenRole.hiringReason}).</p>
 *
 * @param summaryParagraph     Short narrative summary of the candidate (1-2 paragraphs).
 * @param practiceMatchScore   0.0 – 1.0 — how well the candidate fits the desired practice.
 * @param levelMatchScore      0.0 – 1.0 — fit against the desired career level.
 * @param consultingPotential  0.0 – 1.0 — model-estimated probability the candidate would
 *                             succeed as a Trustworks consultant.
 * @param concerns             Bullet-point red flags or gaps the operator should weigh.
 * @param evidenceCitations    Pointers back to source segments (CV pages, role brief, etc.)
 *                             that justify each score; format is prompt-defined.
 */
public record CandidateSummaryOutput(
        String summaryParagraph,
        BigDecimal practiceMatchScore,
        BigDecimal levelMatchScore,
        BigDecimal consultingPotential,
        List<String> concerns,
        List<String> evidenceCitations
) {}
