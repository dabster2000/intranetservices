package dk.trustworks.intranet.aggregates.consultant.dto;

import dk.trustworks.intranet.aggregates.consultant.model.ConsultantProfile;
import dk.trustworks.intranet.aggregates.consultant.services.CvHighlightsExtractor.CvHighlights;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit. Guards the frozen wire contract the dashboard card depends on: exactly one entry per
 * requested uuid, list fields never JSON {@code null}, {@code status} never null, and
 * {@code pitchText}/{@code generatedAt} non-null ONLY when {@code status == READY}.
 */
class ConsultantProfileDTOTest {

    private static ConsultantProfile ready(String pitch, String industriesJson, String skillsJson) {
        ConsultantProfile profile = new ConsultantProfile("user-uuid");
        profile.updateFrom(pitch, industriesJson, skillsJson, LocalDateTime.now());
        return profile;
    }

    // ---- parseJsonArray: the cache-poisoning path ------------------------------

    @Test
    void parseJsonArray_treatsTheLiteralNullStringAsEmpty() {
        // Jackson serialises a MissingNode as the 4-character string "null". It is valid JSON,
        // passes MariaDB's JSON column check, and DESERIALISES TO JAVA null WITHOUT THROWING —
        // so it slipped past both the blank guard and the catch and reached the wire as
        // "industries": null, which is exactly the reported bug.
        assertEquals(List.of(), ConsultantProfileDTO.parseJsonArray("null"));
    }

    @Test
    void parseJsonArray_neverReturnsNull_forAnyInput() {
        assertEquals(List.of(), ConsultantProfileDTO.parseJsonArray(null));
        assertEquals(List.of(), ConsultantProfileDTO.parseJsonArray(""));
        assertEquals(List.of(), ConsultantProfileDTO.parseJsonArray("   "));
        assertEquals(List.of(), ConsultantProfileDTO.parseJsonArray("[]"));
        assertEquals(List.of(), ConsultantProfileDTO.parseJsonArray("{\"not\":\"an array\"}"));
        assertEquals(List.of(), ConsultantProfileDTO.parseJsonArray("not json at all"));
        assertEquals(List.of("Finance"), ConsultantProfileDTO.parseJsonArray("[\"Finance\"]"));
    }

    // ---- status derivation -----------------------------------------------------

    @Test
    void noCachedRow_isPending_butStillCarriesTheDeterministicCvFacts() {
        CvHighlights facts = new CvHighlights("Senior Developer", List.of("Public Sector"),
                List.of("Nordea"), 12, 4, 2014);

        ConsultantProfileDTO dto = ConsultantProfileDTO.from("user-uuid", null, facts);

        assertEquals(ConsultantProfile.STATUS_PENDING, dto.status());
        assertNull(dto.pitchText());
        assertNull(dto.generatedAt());
        assertEquals("Senior Developer", dto.roleTitle());
        assertEquals(List.of("Nordea"), dto.topClients());
        assertEquals(List.of("Public Sector"), dto.industries());
        assertEquals(12, dto.projectCount());
        assertEquals(4, dto.clientCount());
        assertEquals(2014, dto.firstProjectYear());
    }

    @Test
    void aPreV461RowWithNoStatus_rendersAsPending_ratherThanBeingDropped() {
        ConsultantProfile legacy = new ConsultantProfile("user-uuid");
        legacy.setStatus(null);
        legacy.setPitchText("A cached pitch from before the migration");

        ConsultantProfileDTO dto = ConsultantProfileDTO.from("user-uuid", legacy, CvHighlights.empty());

        assertEquals(ConsultantProfile.STATUS_PENDING, dto.status());
        // Not READY ⇒ the pitch must NOT be presented as the consultant's own copy.
        assertNull(dto.pitchText());
        assertNull(dto.generatedAt());
    }

    @Test
    void unavailableRow_keepsItsStatus_andExposesNoPitch() {
        ConsultantProfile parked = new ConsultantProfile("user-uuid");
        parked.setStatus(ConsultantProfile.STATUS_UNAVAILABLE);
        parked.setPitchText("stale");

        ConsultantProfileDTO dto = ConsultantProfileDTO.from("user-uuid", parked, CvHighlights.empty());

        assertEquals(ConsultantProfile.STATUS_UNAVAILABLE, dto.status());
        assertNull(dto.pitchText());
    }

    @Test
    void readyRow_exposesThePitchAndGeneratedAt() {
        ConsultantProfileDTO dto = ConsultantProfileDTO.from("user-uuid",
                ready("  Drives digital transformation at scale.  ", "[\"Energy\"]", "[\"Agile Methods\"]"),
                CvHighlights.empty());

        assertEquals(ConsultantProfile.STATUS_READY, dto.status());
        assertEquals("Drives digital transformation at scale.", dto.pitchText());
        assertNotNull(dto.generatedAt());
    }

    // ---- merge rules -----------------------------------------------------------

    @Test
    void deterministicIndustriesWinOutright_overTheCachedAiList() {
        ConsultantProfileDTO dto = ConsultantProfileDTO.from("user-uuid",
                ready("pitch", "[\"Retail\",\"Telco\"]", "[\"Java\"]"),
                new CvHighlights(null, List.of("Public Sector", "Energy & Utilities"), List.of(), 0, 0, null));

        assertEquals(List.of("Public Sector", "Energy & Utilities"), dto.industries());
    }

    @Test
    void theAiIndustryListIsUsedOnlyWhenNothingCouldBeDerived() {
        ConsultantProfileDTO dto = ConsultantProfileDTO.from("user-uuid",
                ready("pitch", "[\"Retail\",\"Telco\",\"Media\",\"Legal\"]", "[\"Java\"]"),
                CvHighlights.empty());

        // Capped at the AI cap (3), not the tighter derived cap (2).
        assertEquals(List.of("Retail", "Telco", "Media"), dto.industries());
    }

    @Test
    void aSkillThatDuplicatesAnIndustry_isDropped() {
        ConsultantProfileDTO dto = ConsultantProfileDTO.from("user-uuid",
                ready("pitch", "[]", "[\"energy & utilities\",\"Java\"]"),
                new CvHighlights(null, List.of("Energy & Utilities"), List.of(), 0, 0, null));

        assertEquals(List.of("Energy & Utilities"), dto.industries());
        assertEquals(List.of("Java"), dto.topSkills());
    }

    @Test
    void everyListField_isNeverNull_evenForAConsultantWithNothingAtAll() {
        ConsultantProfileDTO dto = ConsultantProfileDTO.empty("user-uuid");

        assertEquals(ConsultantProfile.STATUS_UNAVAILABLE, dto.status());
        assertTrue(dto.industries().isEmpty());
        assertTrue(dto.topSkills().isEmpty());
        assertTrue(dto.topClients().isEmpty());
        assertEquals("user-uuid", dto.useruuid());
    }

    @Test
    void aPoisonedRow_doesNotLeakTheLiteralNullOntoTheWire() {
        // The exact production shape V461 repairs: pitch stamped, both JSON columns holding the
        // 4-character string "null", status READY.
        ConsultantProfile poisoned = ready("A pitch", "null", "null");

        ConsultantProfileDTO dto = ConsultantProfileDTO.from("user-uuid", poisoned, CvHighlights.empty());

        assertNotNull(dto.industries());
        assertNotNull(dto.topSkills());
        assertTrue(dto.industries().isEmpty());
        assertTrue(dto.topSkills().isEmpty());
    }
}
