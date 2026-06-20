package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContactFormMapperTest {

    private static MultivaluedMap<String, String> form(String... kv) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.add(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void mapsCoreParamsToTypedColumns() {
        ConferenceParticipant p = ContactFormMapper.fromForm(form(
                "name", "Ada", "email", "ada@x.dk", "company", "Trustworks",
                "title", "Partner", "message", "Hi", "consent", "on", "marketing", "on"));
        assertEquals("Ada", p.getName());
        assertEquals("ada@x.dk", p.getEmail());
        assertEquals("Trustworks", p.getCompany());
        assertEquals("Partner", p.getTitel());
        assertEquals("Hi", p.getAndet());
        assertTrue(p.isSamtykke());
        assertTrue(p.isMarketingConsent());
    }

    @Test
    void putsUnknownParamsIncludingPhoneInBag() {
        ConferenceParticipant p = ContactFormMapper.fromForm(form(
                "email", "a@b.dk", "phone", "+45 11 22 33 44", "branche", "IT"));
        assertEquals("+45 11 22 33 44", p.getFields().get("phone"));
        assertEquals("IT", p.getFields().get("branche"));
        assertFalse(p.getFields().containsKey("email"), "typed key must not leak into bag");
    }

    @Test
    void checkboxLeniencyAndDefaults() {
        assertTrue(ContactFormMapper.fromForm(form("consent", "true")).isSamtykke());
        assertTrue(ContactFormMapper.fromForm(form("consent", "JA")).isSamtykke());
        assertTrue(ContactFormMapper.fromForm(form("consent", "1")).isSamtykke());
        assertFalse(ContactFormMapper.fromForm(form("name", "X")).isSamtykke(), "absent consent -> false");
        assertFalse(ContactFormMapper.fromForm(form("marketing", "off")).isMarketingConsent());
    }

    @Test
    void sparseFormDoesNotThrow() {
        ConferenceParticipant p = ContactFormMapper.fromForm(form());
        assertNull(p.getEmail());
        assertNotNull(p.getFields());
        assertTrue(p.getFields().isEmpty());
    }
}
