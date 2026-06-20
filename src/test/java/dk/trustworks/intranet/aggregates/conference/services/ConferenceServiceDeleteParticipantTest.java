package dk.trustworks.intranet.aggregates.conference.services;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ConferenceServiceDeleteParticipantTest {

    @Inject
    ConferenceService conferenceService;

    private ConferenceParticipant persistRow(String conf, String pid) {
        ConferenceParticipant p = new ConferenceParticipant();
        p.setUuid(UUID.randomUUID().toString());
        p.setConferenceuuid(conf);
        p.setParticipantuuid(pid);
        p.setEmail("x@y.dk");
        p.setName("Tester");
        p.setRegistered(LocalDateTime.now());
        p.persist();
        return p;
    }

    @Test
    @Transactional
    void deletesAllRowsForParticipantAndIsIdempotent() {
        String conf = "conf-" + UUID.randomUUID();
        String pid = "pid-" + UUID.randomUUID();

        // Append-only history: two rows, same (conferenceuuid, participantuuid)
        persistRow(conf, pid);
        persistRow(conf, pid);
        // A different participant in the same conference must NOT be touched
        persistRow(conf, "pid-other");
        ConferenceParticipant.flush();

        long deleted = conferenceService.deleteParticipant(conf, pid);
        assertEquals(2, deleted, "must delete every row for the participant");
        assertEquals(0, ConferenceParticipant.count("conferenceuuid = ?1 and participantuuid = ?2", conf, pid));
        assertEquals(1, ConferenceParticipant.count("conferenceuuid = ?1", conf), "other participant untouched");

        // Idempotent: deleting again removes nothing
        long deletedAgain = conferenceService.deleteParticipant(conf, pid);
        assertEquals(0, deletedAgain);
    }
}
