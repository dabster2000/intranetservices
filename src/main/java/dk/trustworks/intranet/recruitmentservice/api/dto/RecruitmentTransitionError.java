package dk.trustworks.intranet.recruitmentservice.api.dto;

import java.util.List;

public record RecruitmentTransitionError(String error, int status, List<String> allowedTransitions) {}
