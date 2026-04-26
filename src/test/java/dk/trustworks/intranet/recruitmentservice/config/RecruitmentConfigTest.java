package dk.trustworks.intranet.recruitmentservice.config;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RecruitmentConfigTest.EnabledProfile.class)
class RecruitmentConfigTest {

    @Inject RecruitmentConfig config;

    @Test
    void recruitmentEnabledFlagReadsFromConfig() {
        assertTrue(config.isEnabled(), "recruitment.enabled=true should be visible to the bean");
        assertFalse(config.aiEnabled(), "recruitment.ai.enabled defaults to false");
        assertFalse(config.outlookEnabled());
        assertFalse(config.slackEnabled());
        assertFalse(config.nextsignEnabled());
        assertFalse(config.sharepointEnabled());
    }

    @Test
    void perKindAiFlagsDefaultDisabled() {
        assertFalse(config.aiCvExtractionEnabled(), "recruitment.ai.cv-extraction.enabled defaults false");
        assertFalse(config.aiRoleBriefEnabled(), "recruitment.ai.role-brief.enabled defaults false");
        assertFalse(config.aiCandidateSummaryEnabled(), "recruitment.ai.candidate-summary.enabled defaults false");
    }

    public static class EnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("recruitment.enabled", "true");
        }
    }
}
