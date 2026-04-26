package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Scorecard;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ScorecardRecommendation;

import java.time.LocalDateTime;
import java.util.List;

public class ScorecardResponse {
    public String uuid;
    public String interviewUuid;
    public String interviewerUserUuid;
    public Byte practiceSkillFit;
    public Byte careerLevelFit;
    public Byte consultingCommunication;
    public Byte clientFacingMaturity;
    public Byte cultureValueFit;
    public Byte deliveryTrackPotential;
    public String concerns;
    public ScorecardRecommendation recommendation;
    public String notes;
    public String privateNotes;                   // STRIPPED by RecruitmentScopeResponseFilter
    public LocalDateTime submittedAt;
    public LocalDateTime reopenedAt;
    public String reopenedByUuid;
    public String reopenedReason;
    public List<ScorecardAmendmentResponse> amendments;

    public static ScorecardResponse from(Scorecard s, List<ScorecardAmendmentResponse> amendments) {
        ScorecardResponse r = new ScorecardResponse();
        r.uuid = s.uuid;
        r.interviewUuid = s.interviewUuid;
        r.interviewerUserUuid = s.interviewerUserUuid;
        r.practiceSkillFit = s.practiceSkillFit;
        r.careerLevelFit = s.careerLevelFit;
        r.consultingCommunication = s.consultingCommunication;
        r.clientFacingMaturity = s.clientFacingMaturity;
        r.cultureValueFit = s.cultureValueFit;
        r.deliveryTrackPotential = s.deliveryTrackPotential;
        r.concerns = s.concerns;
        r.recommendation = s.recommendation;
        r.notes = s.notes;
        r.privateNotes = s.privateNotes;
        r.submittedAt = s.submittedAt;
        r.reopenedAt = s.reopenedAt;
        r.reopenedByUuid = s.reopenedByUuid;
        r.reopenedReason = s.reopenedReason;
        r.amendments = amendments == null ? List.of() : amendments;
        return r;
    }
}
