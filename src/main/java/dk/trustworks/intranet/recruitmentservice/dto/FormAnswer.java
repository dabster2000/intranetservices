package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * One public-form answer for the P8 profile's Application tab
 * ({@code IFormAnswer} in the FE↔BE contract). {@code label} is the Danish
 * wording from {@code PublicApplyQuestions}; unknown/legacy keys fall back
 * to the key itself so old answers always render.
 */
public record FormAnswer(
        String questionKey,
        String label,
        String answer
) {
}
