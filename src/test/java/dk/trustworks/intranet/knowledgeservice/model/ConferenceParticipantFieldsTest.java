package dk.trustworks.intranet.knowledgeservice.model;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ConferenceParticipantFieldsTest {

    @Test
    @Transactional
    void persistsAndReadsBackFieldsAndMarketingConsent() {
        String uuid = UUID.randomUUID().toString();
        ConferenceParticipant p = new ConferenceParticipant();
        p.setUuid(uuid);
        p.setConferenceuuid("test-conf");
        p.setEmail("a@b.dk");
        p.setRegistered(LocalDateTime.now());
        p.setMarketingConsent(true);
        Map<String, Object> bag = new LinkedHashMap<>();
        bag.put("phone", "+45 12 34 56 78");
        p.setFields(bag);
        p.persist();

        ConferenceParticipant reloaded = ConferenceParticipant.findById(uuid);
        assertTrue(reloaded.isMarketingConsent());
        assertEquals("+45 12 34 56 78", reloaded.getFields().get("phone"));
    }
}
