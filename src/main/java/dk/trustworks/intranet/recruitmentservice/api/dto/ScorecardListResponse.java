package dk.trustworks.intranet.recruitmentservice.api.dto;

import java.util.List;

public record ScorecardListResponse(
    ScorecardResponse ownScorecard,    // nullable
    List<ScorecardResponse> others
) {}
