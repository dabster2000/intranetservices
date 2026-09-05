package dk.trustworks.intranet.aggregates.userprofile.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.userprofile.dto.UserProfileExtensionRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Spec §4.6.3 — profile validation and CV competence extraction, container-free. */
class ProfileExtensionRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);
    private static final List<String> CODES = List.of("DEV", "ADV", "DATA");

    @Test
    void acceptsAValidProfileAndTheNoDisciplineSentinel() {
        assertNull(ProfileExtensionRules.problem(
                new UserProfileExtensionRequest("Cand.merc. CBS", LocalDate.of(2028, 6, 30), "KANDIDAT", "DEV"), CODES, TODAY));
        assertNull(ProfileExtensionRules.problem(
                new UserProfileExtensionRequest(null, null, "BACHELOR", "UD"), CODES, TODAY));
        assertNull(ProfileExtensionRules.problem(
                new UserProfileExtensionRequest(null, null, null, null), CODES, TODAY));
    }

    @Test
    void studyLevelIsExplicitAndTwoValued() {
        assertNotNull(ProfileExtensionRules.problem(
                new UserProfileExtensionRequest(null, null, "MASTER", null), CODES, TODAY));
        assertNotNull(ProfileExtensionRules.problem(
                new UserProfileExtensionRequest(null, null, "bachelor", null), CODES, TODAY));
    }

    @Test
    void disciplineMustBeAnActivePracticeCodeOrUd() {
        assertNotNull(ProfileExtensionRules.problem(
                new UserProfileExtensionRequest(null, null, null, "SALES"), CODES, TODAY));
        assertNotNull(ProfileExtensionRules.problem(
                new UserProfileExtensionRequest(null, null, null, "DEV"), List.of(), TODAY));
    }

    @Test
    void graduationAndEducationAreBounded() {
        assertNotNull(ProfileExtensionRules.problem(
                new UserProfileExtensionRequest(null, TODAY.plusYears(16), null, null), CODES, TODAY));
        assertNotNull(ProfileExtensionRules.problem(
                new UserProfileExtensionRequest("x".repeat(256), null, null, null), CODES, TODAY));
        assertNull(ProfileExtensionRules.problem(
                new UserProfileExtensionRequest("x".repeat(255), TODAY.plusYears(15), null, null), CODES, TODAY));
    }

    @Test
    void tagsAreNormalisedAndComparedCaseInsensitively() {
        assertEquals("Java Spring", ProfileExtensionRules.normalizeTag("  Java   Spring  "));
        assertNull(ProfileExtensionRules.normalizeTag("   "));
        assertNull(ProfileExtensionRules.normalizeTag(null));
        assertEquals(64, ProfileExtensionRules.normalizeTag("a".repeat(80)).length());
        assertEquals(ProfileExtensionRules.tagKey("Java"), ProfileExtensionRules.tagKey("JAVA"));
    }

    @Test
    void extractsDistinctCompetenceTitlesInDocumentOrder() throws Exception {
        JsonNode root = new ObjectMapper().readTree("""
                {"competencies":[
                  {"title":"Java"},{"title":"  Python "},{"title":"java"},{"title":""},{"name":"no title"},{"title":42}
                ],"other":1}
                """);
        assertEquals(List.of("Java", "Python"), ProfileExtensionRules.extractCompetenceTitles(root));
    }

    @Test
    void toleratesMissingOrMalformedCompetencies() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertEquals(List.of(), ProfileExtensionRules.extractCompetenceTitles(null));
        assertEquals(List.of(), ProfileExtensionRules.extractCompetenceTitles(om.readTree("{}")));
        assertEquals(List.of(), ProfileExtensionRules.extractCompetenceTitles(om.readTree("{\"competencies\":\"nope\"}")));
    }
}
