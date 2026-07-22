package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * One candidate-scoped form answer on a triage-queue card: the stable
 * question key, its display label (resolved from
 * {@code PublicApplyQuestions} — the UI never interprets keys) and the
 * candidate's own words.
 */
public record TriageQueueAnswer(String questionKey, String label, String answer) {
}
