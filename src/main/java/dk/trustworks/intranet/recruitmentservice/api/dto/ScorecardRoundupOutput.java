package dk.trustworks.intranet.recruitmentservice.api.dto;

import java.util.List;

/**
 * Typed projection of {@code AiArtifact.output} for {@code SCORECARD_ROUNDUP} artifacts.
 *
 * <p>Mirrors {@code META-INF/recruitment/schemas/scorecard-roundup-v1.json}.
 * Advisory only — the round-up handler ({@link
 * dk.trustworks.intranet.recruitmentservice.application.handlers.ScorecardRoundupApplyHandler})
 * does not patch the Interview row; the team lead reads this summary and copies
 * the relevant fields manually into {@code POST /interviews/{uuid}/round-up}.
 */
public class ScorecardRoundupOutput {
    public String consensus;
    public String dissent;
    public List<String> risks;
    public String recommendation;
    public String summaryParagraph;
}
