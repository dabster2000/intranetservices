package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;

import java.time.LocalDateTime;

public record CandidateResponse(
        String uuid, String firstName, String lastName, String email, String phone,
        String currentCompany, Practice desiredPractice, String desiredCareerLevelUuid,
        Integer noticePeriodDays, Integer salaryExpectation, String salaryCurrency,
        String locationPreference, String linkedinUrl, String firstContactSource,
        String consentStatus, LocalDateTime consentGivenAt, LocalDateTime consentExpiresAt,
        CandidateState state, String ownerUserUuid,
        LocalDateTime addedToPoolAt, LocalDateTime retentionExtendedTo,
        LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static CandidateResponse from(Candidate c) {
        return new CandidateResponse(
                c.uuid, c.firstName, c.lastName, c.email, c.phone,
                c.currentCompany, c.desiredPractice, c.desiredCareerLevelUuid,
                c.noticePeriodDays, c.salaryExpectation, c.salaryCurrency,
                c.locationPreference, c.linkedinUrl, c.firstContactSource,
                c.consentStatus, c.consentGivenAt, c.consentExpiresAt,
                c.state, c.ownerUserUuid, c.addedToPoolAt, c.retentionExtendedTo,
                c.createdAt, c.updatedAt);
    }
}
