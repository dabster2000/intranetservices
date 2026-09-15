package dk.trustworks.intranet.aggregates.conference.events;

import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import jakarta.persistence.Entity;

/** Read-compatible discriminator for the synchronous withdrawal audit; never asynchronously projected. */
@Entity
public class ConferenceUnsubscribeEvent extends AggregateRootChangeEvent {
    public ConferenceUnsubscribeEvent() {}
}
