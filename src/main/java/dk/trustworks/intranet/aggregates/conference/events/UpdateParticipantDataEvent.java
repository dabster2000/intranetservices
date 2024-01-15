package dk.trustworks.intranet.aggregates.conference.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.UPDATE_CONFERENCE_PARTICIPANT;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class UpdateParticipantDataEvent extends AggregateRootChangeEvent {

    public UpdateParticipantDataEvent(String aggregateRootUUID, ConferenceParticipant item) {
        super(aggregateRootUUID, UPDATE_CONFERENCE_PARTICIPANT, JsonObject.mapFrom(item).encode());
    }
}
