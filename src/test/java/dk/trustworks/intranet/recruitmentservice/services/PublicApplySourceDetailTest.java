package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P5 {@code source_detail} assembly: the free-text follow-up maps to a
 * source-specific key so downstream triage/reporting reads structured
 * data, and the unsolicited practice preference is carried with its
 * resolved name. Plain unit test, no framework.
 */
class PublicApplySourceDetailTest {

    @Test
    void followUp_mapsToSourceSpecificKey() {
        assertEquals("referenceName", followUpKeyFor("NETWORK"));
        assertEquals("channel", followUpKeyFor("SOME"));
        assertEquals("eventName", followUpKeyFor("CONFERENCE"));
        assertEquals("eventName", followUpKeyFor("TW_EVENT"));
        assertEquals("jobListingRef", followUpKeyFor("JOB_LISTING"));
    }

    @Test
    void linkedinAndOther_ignoreTheFollowUp() {
        for (String source : new String[]{"LINKEDIN", "OTHER"}) {
            Map<String, Object> detail = PublicApplyService.buildSourceDetail(
                    source, "should be dropped", null, null);
            assertEquals(Map.of("selfReportedSource", source), detail,
                    source + " carries no follow-up key");
        }
    }

    @Test
    void selfReportedSource_isNormalised() {
        Map<String, Object> detail = PublicApplyService.buildSourceDetail(
                " network ", " Jane Referrer ", null, null);
        assertEquals("NETWORK", detail.get("selfReportedSource"));
        assertEquals("Jane Referrer", detail.get("referenceName"));
    }

    @Test
    void followUpWithoutSource_isDropped() {
        Map<String, Object> detail = PublicApplyService.buildSourceDetail(
                null, "orphan follow-up", null, null);
        assertTrue(detail.isEmpty(), "a follow-up without a source has no key to land on");
    }

    @Test
    void desiredPractice_carriesUuidAndResolvedName() {
        Map<String, Object> detail = PublicApplyService.buildSourceDetail(
                null, null, "practice-uuid-1", "Public & Management");
        assertEquals("practice-uuid-1", detail.get("desiredPracticeUuid"));
        assertEquals("Public & Management", detail.get("desiredPracticeName"));
    }

    @Test
    void allSections_combine() {
        Map<String, Object> detail = PublicApplyService.buildSourceDetail(
                "CONFERENCE", "IT-dagen 2026", "practice-uuid-2", "Tech");
        assertEquals("CONFERENCE", detail.get("selfReportedSource"));
        assertEquals("IT-dagen 2026", detail.get("eventName"));
        assertEquals("practice-uuid-2", detail.get("desiredPracticeUuid"));
        assertEquals("Tech", detail.get("desiredPracticeName"));
    }

    @Test
    void emptyInput_yieldsEmptyMap() {
        assertTrue(PublicApplyService.buildSourceDetail(null, null, null, null).isEmpty());
        assertTrue(PublicApplyService.buildSourceDetail(" ", " ", null, null).isEmpty());
    }

    private static String followUpKeyFor(String source) {
        Map<String, Object> detail = PublicApplyService.buildSourceDetail(
                source, "follow-up", null, null);
        assertEquals("follow-up", detail.values().stream()
                        .filter(v -> !v.equals(source)).findFirst().orElse(null),
                "follow-up text must land under the mapped key for " + source);
        return detail.keySet().stream()
                .filter(k -> !k.equals("selfReportedSource"))
                .findFirst().orElse(null);
    }
}
