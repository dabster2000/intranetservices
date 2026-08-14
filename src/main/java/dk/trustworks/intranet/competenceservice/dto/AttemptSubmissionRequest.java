package dk.trustworks.intranet.competenceservice.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The body of {@code POST /me/attempts/{uuid}/submit} — {@code {"answers":{"q1":"q1o3",…}}}.
 *
 * <p>Option <em>ids</em>, never texts and never indices. Ids survive the shuffle, so the
 * server can score against the frozen payload without caring what order the candidate saw;
 * an index would be scored against whatever order the server reconstructed, and a
 * reconstruction bug would mark right answers wrong for everybody at once.
 *
 * <p>There is no useruuid in this body and no useruuid in the path. The subject is
 * {@code RequestHeaderHolder.getUserUuid()}, so there is nothing to tamper with (§10.1), and
 * ownership of the attempt is verified before the body is looked at.
 *
 * @param answers questionId → chosen optionId. {@code null} is preserved rather than
 *                normalised to an empty map: {@code CompetenceTestScorer} answers a null
 *                submission with {@code 400 "No answers submitted"}, which is a better
 *                message than the {@code 400} an empty map earns ("All N questions must be
 *                answered (received 0)"), and quietly turning a missing body into a
 *                zero-score submission would burn the candidate's attempt.
 */
public record AttemptSubmissionRequest(Map<String, String> answers) {

    public AttemptSubmissionRequest {
        // Defensive copy that tolerates null values — Map.copyOf would throw on
        // {"q1": null}, turning a 400 the scorer already words well into a 500.
        answers = answers == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
    }
}
