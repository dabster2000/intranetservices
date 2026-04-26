package dk.trustworks.intranet.recruitmentservice;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class AiEnabledTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "recruitment.ai.enabled", "true",
            "recruitment.ai.cv-extraction.enabled", "true",
            "recruitment.ai.role-brief.enabled", "true",
            "recruitment.ai.candidate-summary.enabled", "true"
        );
    }
}
