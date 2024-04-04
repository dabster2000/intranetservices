package dk.trustworks.intranet.aggregates;

import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.BROWSER_EVENT;

@Path("/sse")
@ApplicationScoped
public class SSEResource {

    private final BroadcastProcessor<String> broadcastProcessor = BroadcastProcessor.create();

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    //@SseElementType(MediaType.TEXT_PLAIN)
    public Multi<String> stream() {
        System.out.println("SSEResource.stream");
        return broadcastProcessor;
    }

    @ConsumeEvent(BROWSER_EVENT)
    public void consume(String aggregateUUID) {
        System.out.println("SSEResource.consume: " + aggregateUUID);
        broadcastProcessor.onNext(aggregateUUID);
    }

}
