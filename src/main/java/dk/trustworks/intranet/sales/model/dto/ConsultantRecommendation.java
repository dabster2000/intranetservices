package dk.trustworks.intranet.sales.model.dto;

/**
 * A single AI-generated consultant recommendation for a sales lead.
 *
 * @param consultantUuid    UUID of the recommended consultant.
 * @param consultantName    Full name of the consultant.
 * @param matchScore        Match score between 0.0 and 1.0 (higher is better).
 * @param rationale         AI-generated explanation for this recommendation.
 * @param availabilityNote  Short note about the consultant's availability relative to the lead period.
 */
public record ConsultantRecommendation(
        String consultantUuid,
        String consultantName,
        double matchScore,
        String rationale,
        String availabilityNote
) {}
