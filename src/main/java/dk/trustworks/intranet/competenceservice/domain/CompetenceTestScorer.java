package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.content.CompetenceContent;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scores a submission on the server, against the version frozen onto the attempt.
 *
 * <p>The prototype shipped {@code [["answer text", true], ...]} to the browser and scored
 * there. Since the entire point of the module is trustworthy evidence, that is
 * disqualifying: anyone could read the DOM or fake the record. Here the correct flag never
 * leaves the server, the submission carries option ids only, and the comparison happens
 * against the payload the attempt was started under — not the current ACTIVE version,
 * which may have been republished mid-attempt.
 *
 * <p>Pure and deterministic, so every branch is table-testable in the fast tier.
 */
public final class CompetenceTestScorer {

    private CompetenceTestScorer() {
    }

    /**
     * @param correctCount  how many were right
     * @param questionCount how many were asked
     * @param score         {@code correctCount / questionCount}, 4 dp
     * @param passed        {@code score >= threshold}
     */
    public record Result(int correctCount, int questionCount, BigDecimal score, boolean passed) {
    }

    /**
     * @param payload   the frozen test version
     * @param answers   questionId → chosen optionId
     * @param threshold the threshold frozen on the attempt at start — never the current
     *                  global setting, which is why raising it later cannot retroactively
     *                  fail anyone
     */
    public static Result score(CompetenceContent.TestPayload payload,
                               Map<String, String> answers,
                               BigDecimal threshold) {
        if (payload == null || payload.questions().isEmpty()) {
            throw new WebApplicationException("Test version has no questions",
                    Response.Status.BAD_REQUEST);
        }
        if (answers == null) {
            throw new WebApplicationException("No answers submitted", Response.Status.BAD_REQUEST);
        }

        // Every submitted key must name a question in the frozen version. Catching this
        // before scoring means a malformed body is a 400 with no partial score written,
        // rather than a silently low mark.
        Set<String> known = new LinkedHashSet<>();
        payload.questions().forEach(q -> known.add(q.id()));
        for (String questionId : answers.keySet()) {
            if (!known.contains(questionId)) {
                throw new WebApplicationException(
                        "Unknown question id in submission: " + questionId,
                        Response.Status.BAD_REQUEST);
            }
        }

        int questionCount = payload.questions().size();
        if (answers.size() != questionCount) {
            throw new WebApplicationException(
                    "All " + questionCount + " questions must be answered (received "
                            + answers.size() + ")",
                    Response.Status.BAD_REQUEST);
        }

        int correct = 0;
        for (CompetenceContent.Question question : payload.questions()) {
            String chosen = answers.get(question.id());
            if (chosen == null || chosen.isBlank()) {
                throw new WebApplicationException(
                        "Question " + question.id() + " has no answer", Response.Status.BAD_REQUEST);
            }
            if (!question.hasOption(chosen)) {
                throw new WebApplicationException(
                        "Unknown option id for question " + question.id() + ": " + chosen,
                        Response.Status.BAD_REQUEST);
            }
            CompetenceContent.Option correctOption = question.correctOption();
            if (correctOption == null) {
                // A published version always has exactly one correct option per question —
                // the publish validator refuses otherwise. Reaching here means the row was
                // written around the service, so refuse rather than score it as wrong.
                throw new WebApplicationException(
                        "Frozen test version is malformed: question " + question.id()
                                + " has no correct option",
                        Response.Status.INTERNAL_SERVER_ERROR);
            }
            if (correctOption.id().equals(chosen)) {
                correct++;
            }
        }

        BigDecimal score = BigDecimal.valueOf(correct)
                .divide(BigDecimal.valueOf(questionCount), 4, RoundingMode.HALF_UP);
        boolean passed = threshold == null || score.compareTo(threshold) >= 0;
        return new Result(correct, questionCount, score, passed);
    }
}
