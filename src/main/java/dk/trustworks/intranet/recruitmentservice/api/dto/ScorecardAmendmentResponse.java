package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.ScorecardAmendment;
import java.time.LocalDateTime;

public class ScorecardAmendmentResponse {
    public String uuid;
    public String scorecardUuid;
    public String authorUuid;
    public String body;
    public LocalDateTime createdAt;

    public static ScorecardAmendmentResponse from(ScorecardAmendment a) {
        ScorecardAmendmentResponse r = new ScorecardAmendmentResponse();
        r.uuid = a.uuid;
        r.scorecardUuid = a.scorecardUuid;
        r.authorUuid = a.authorUuid;
        r.body = a.body;
        r.createdAt = a.createdAt;
        return r;
    }
}
