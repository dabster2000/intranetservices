package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CandidateCreateRequest(
        @Size(max = 120) String firstName,
        @Size(max = 120) String lastName,
        @Email @Size(max = 255) String email,
        @Size(max = 40) String phone,
        String currentCompany,
        Practice desiredPractice,
        String desiredCareerLevelUuid,
        Integer noticePeriodDays,
        Integer salaryExpectation,
        String salaryCurrency,
        String locationPreference,
        String linkedinUrl,
        String firstContactSource,
        List<String> tags) {}
