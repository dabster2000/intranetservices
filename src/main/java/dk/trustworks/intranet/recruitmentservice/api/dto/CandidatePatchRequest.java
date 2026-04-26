package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;

import java.util.List;

public record CandidatePatchRequest(
        String firstName, String lastName, String email, String phone,
        String currentCompany, Practice desiredPractice, String desiredCareerLevelUuid,
        Integer noticePeriodDays, Integer salaryExpectation, String salaryCurrency,
        String locationPreference, String linkedinUrl,
        List<String> tags) {}
