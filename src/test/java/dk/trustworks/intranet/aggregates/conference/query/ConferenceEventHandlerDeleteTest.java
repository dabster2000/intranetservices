package dk.trustworks.intranet.aggregates.conference.query;

import dk.trustworks.intranet.aggregates.conference.events.DeleteParticipantEvent;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ConferenceEventHandlerDeleteTest {

    @Inject
    ConferenceEventHandler handler;

    private void persistRow(String conf, String pid) {
        ConferenceParticipant p = new ConferenceParticipant();
        p.setUuid(UUID.randomUUID().toString());
        p.setConferenceuuid(conf);
        p.setParticipantuuid(pid);
        p.setEmail("x@y.dk");
        p.setRegistered(LocalDateTime.now());
        p.persist();
    }

    @Test
    @Transactional
    void consumesEnvelopeAndDeletesOnlyTargetedParticipant() {
        String conf = "conf-" + UUID.randomUUID();
        String pid = "pid-" + UUID.randomUUID();
        persistRow(conf, pid);
        persistRow(conf, pid);
        persistRow(conf, "pid-keep");
        ConferenceParticipant.flush();

        ConferenceParticipant stub = new ConferenceParticipant();
        stub.setConferenceuuid(conf);
        stub.setParticipantuuid(pid);
        DomainEventEnvelope env = DomainEventEnvelope.fromAggregateEvent(new DeleteParticipantEvent(conf, stub));

        handler.onDeleteConferenceParticipant(env.toJson());

        assertEquals(0, ConferenceParticipant.count("conferenceuuid = ?1 and participantuuid = ?2", conf, pid));
        assertEquals(1, ConferenceParticipant.count("conferenceuuid = ?1 and participantuuid = ?2", conf, "pid-keep"));
    }
}
