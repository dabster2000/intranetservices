package dk.trustworks.intranet.aggregates.conference.dto;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParticipantViewTest {

    @Test
    void mergesTypedColumnsOverBagIntoAllFields() {
        ConferenceParticipant p = new ConferenceParticipant();
        p.setName("Ada");
        p.setCompany("Trustworks");
        p.setEmail("ada@x.dk");
        p.setSamtykke(true);
        p.setMarketingConsent(false);
        Map<String, Object> bag = new LinkedHashMap<>();
        bag.put("phone", "+45 99");
        bag.put("name", "SHOULD_BE_OVERRIDDEN"); // typed column must win
        p.setFields(bag);

        ParticipantView v = ParticipantView.from(p);

        assertEquals("+45 99", v.allFields().get("phone"));
        assertEquals("Ada", v.allFields().get("name"), "typed column authoritative over bag");
        assertEquals(Boolean.TRUE, v.allFields().get("consent"));
        assertEquals(Boolean.FALSE, v.allFields().get("marketing"));
        assertEquals("+45 99", v.fields().get("phone"));
        assertEquals("Ada", v.name());
        assertFalse(v.marketingConsent());
    }

    @Test
    void nullBagYieldsEmptyFieldsAndTypedAllFields() {
        ConferenceParticipant p = new ConferenceParticipant();
        p.setEmail("a@b.dk");
        p.setFields(null);

        ParticipantView v = ParticipantView.from(p);

        assertNotNull(v.fields());
        assertTrue(v.fields().isEmpty());
        assertEquals("a@b.dk", v.allFields().get("email"));
    }
}
