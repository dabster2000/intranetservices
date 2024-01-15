package dk.trustworks.intranet.aggregates.conference.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;

import static dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType.CHANGE_CONFERENCE_PARTICIPANT_PHASE;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
public class ChangeParticipantPhaseEvent extends AggregateRootChangeEvent {

    public ChangeParticipantPhaseEvent(String aggregateRootUUID, ConferenceParticipant item) {
        super(aggregateRootUUID, CHANGE_CONFERENCE_PARTICIPANT_PHASE, JsonObject.mapFrom(item).encode());
    }
}
