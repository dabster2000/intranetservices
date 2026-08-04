package dk.trustworks.intranet.aggregates.consultant.services;

import dk.trustworks.intranet.cvtool.entity.CvToolEmployeeCv;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit — no CDI, no container, no network. Covers the parts of
 * {@link ConsultantProfileGenerationService} that are pure: the model/effort/budget configuration
 * that reaches {@code OpenAIService}, and the deterministic CV selection that the read path and the
 * write path must agree on.
 */
class ConsultantProfileGenerationServiceTest {

    private static CvToolEmployeeCv cv(String uuid, LocalDateTime updatedAt) {
        CvToolEmployeeCv entity = new CvToolEmployeeCv();
        entity.setUuid(uuid);
        entity.setUseruuid("user-uuid");
        entity.setCvDataJson("{}");
        entity.setLastSyncedAt(LocalDateTime.now());
        entity.setCvLastUpdatedAt(updatedAt);
        return entity;
    }

    /**
     * Asserted as a literal, not against the constant itself — comparing
     * {@code MAX_OUTPUT_TOKENS} to {@code MAX_OUTPUT_TOKENS} would be a tautology that passes
     * however the value drifts. 4096 with no reasoning-effort pin is the exact configuration that
     * burned its whole budget on hidden reasoning and returned no output text (staging 2026-08-01).
     */
    @Test
    void outputBudget_isTheRaisedValue_notTheDefault4096() {
        assertEquals(8192, ConsultantProfileGenerationService.MAX_OUTPUT_TOKENS);
    }

    @Test
    void reasoningEffort_isPinnedWhenConfigured_andOmittedWhenBlank() {
        ConsultantProfileGenerationService service = new ConsultantProfileGenerationService();

        service.profileReasoningEffort = Optional.of("low");
        assertEquals("low", service.reasoningEffortOrNull());

        // An EMPTY value must mean "omit the reasoning node entirely" — required if the model is
        // ever pointed at a gpt-4o-family model, which rejects the node.
        service.profileReasoningEffort = Optional.of("");
        assertNull(service.reasoningEffortOrNull());
        service.profileReasoningEffort = Optional.of("   ");
        assertNull(service.reasoningEffortOrNull());
        service.profileReasoningEffort = Optional.empty();
        assertNull(service.reasoningEffortOrNull());
    }

    /**
     * The {@code @ConfigProperty} defaultValue and the yaml default must stay byte-identical —
     * {@code bug-report.ai.model} already drifted in this repo, and a drift here means the code
     * default silently wins in any environment where the yaml key is absent.
     */
    @Test
    void yamlDefaults_matchTheConfigPropertyDefaults() throws Exception {
        String yaml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(in, "application.yml must be on the test classpath");
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(yaml.contains("consultant-profile-model: ${OPENAI_CONSULTANT_PROFILE_MODEL:gpt-5.6-terra}"),
                "openai.consultant-profile-model default drifted from the @ConfigProperty defaultValue");
        assertTrue(yaml.contains(
                        "consultant-profile-reasoning-effort: ${OPENAI_CONSULTANT_PROFILE_REASONING_EFFORT:low}"),
                "openai.consultant-profile-reasoning-effort default drifted from the @ConfigProperty defaultValue");
        assertTrue(yaml.contains("retry-backoff-minutes: ${CONSULTANT_PROFILE_RETRY_BACKOFF_MINUTES:30}"),
                "consultant-profile.generation.retry-backoff-minutes default drifted");
        assertTrue(yaml.contains("max-attempts: ${CONSULTANT_PROFILE_MAX_ATTEMPTS:3}"),
                "consultant-profile.generation.max-attempts default drifted");
        assertTrue(yaml.contains("batch-size: ${CONSULTANT_PROFILE_PREWARM_BATCH_SIZE:40}"),
                "consultant-profile.prewarm.batch-size default drifted");
    }

    @Test
    void pickCv_selectsTheMostRecentlyUpdatedVariant_deterministically() {
        CvToolEmployeeCv older = cv("cv-a", LocalDateTime.of(2026, 1, 1, 0, 0));
        CvToolEmployeeCv newer = cv("cv-b", LocalDateTime.of(2026, 5, 26, 9, 41));

        assertEquals("cv-b", ConsultantProfileGenerationService.pickCv(List.of(older, newer)).getUuid());
        assertEquals("cv-b", ConsultantProfileGenerationService.pickCv(List.of(newer, older)).getUuid());
    }

    @Test
    void pickCv_breaksTiesOnUuid_soTheReadAndWritePathsCannotDisagree() {
        LocalDateTime same = LocalDateTime.of(2026, 5, 26, 9, 41);

        assertEquals("cv-z", ConsultantProfileGenerationService
                .pickCv(List.of(cv("cv-a", same), cv("cv-z", same))).getUuid());
    }

    @Test
    void pickCv_toleratesNullAndEmptyInput() {
        assertNull(ConsultantProfileGenerationService.pickCv(null));
        assertNull(ConsultantProfileGenerationService.pickCv(List.of()));
        assertEquals("cv-a", ConsultantProfileGenerationService
                .pickCv(List.of(cv("cv-a", null))).getUuid());
    }
}
