package dk.trustworks.intranet.expenseservice.dto;

import java.util.List;

public record PreviewImpactResponseDTO(
    int totalRejected,
    int wouldFlipToApproved,
    int wouldRemainRejected,
    List<String> flippedExpenseUuids
) {}
