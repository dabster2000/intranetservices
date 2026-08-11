package dk.trustworks.intranet.recruitmentservice.model;

import java.util.List;

/**
 * Interviewer coaching for one scorecard subject — what the subject is
 * actually testing, the probes that surface it, and what each of the four
 * scores looks like.
 * <p>
 * Deliberately NOT part of {@link ScorecardAttribute}: the attribute
 * ({@code code} + {@code label}) is snapshotted onto the position at create
 * time because scores key on it, whereas guidance is coaching text that must
 * improve retroactively for every position. Sharpening an anchor next quarter
 * should reach the interview happening tomorrow on a position created last
 * year — so guidance is resolved by {@code code} at render time from
 * {@link ScorecardGuidanceCatalog}, never frozen into the position row.
 *
 * @param code              the {@link ScorecardAttribute#code()} this coaches
 * @param label             display label of the subject
 * @param whatYouAreScoring the definition an interviewer reads before scoring
 * @param probes            questions that surface the subject, in asking order
 * @param anchors           exactly four entries — what a 1, 2, 3 and 4 look like
 */
public record ScorecardGuidance(
        String code,
        String label,
        String whatYouAreScoring,
        List<String> probes,
        List<String> anchors
) {

    /** The number of score anchors — the 1–4 scale, one description each. */
    public static final int ANCHOR_COUNT = 4;

    public ScorecardGuidance {
        probes = probes == null ? List.of() : List.copyOf(probes);
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
        if (!anchors.isEmpty() && anchors.size() != ANCHOR_COUNT) {
            throw new IllegalArgumentException(
                    "Scorecard guidance '" + code + "' must describe exactly "
                            + ANCHOR_COUNT + " scores; got " + anchors.size());
        }
    }

    /**
     * @param score 1..4
     * @return the anchor for that score, or {@code null} when this subject
     *         carries no anchors (custom subjects added by a hiring owner)
     */
    public String anchorFor(int score) {
        if (anchors.isEmpty() || score < 1 || score > ANCHOR_COUNT) {
            return null;
        }
        return anchors.get(score - 1);
    }
}
