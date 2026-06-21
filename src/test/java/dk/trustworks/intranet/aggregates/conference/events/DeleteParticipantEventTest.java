package dk.trustworks.intranet.aggregates.conference.events;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DeleteParticipantEventTest {

    @Test
    void carriesDeleteTypeAndParticipantIdentity() {
        ConferenceParticipant stub = new ConferenceParticipant();
        stub.setConferenceuuid("conf-1");
        stub.setParticipantuuid("part-9");

        DeleteParticipantEvent event = new DeleteParticipantEvent("conf-1", stub);

        assertEquals(AggregateEventType.DELETE_CONFERENCE_PARTICIPANT, event.getEventType());
        assertEquals("conf-1", event.getAggregateRootUUID());
        assertNotNull(event.getUuid());

        JsonObject payload = new JsonObject(event.getEventContent());
        assertEquals("conf-1", payload.getString("conferenceuuid"));
        assertEquals("part-9", payload.getString("participantuuid"));
    }
}
