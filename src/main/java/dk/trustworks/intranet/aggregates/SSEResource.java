package dk.trustworks.intranet.aggregates;

import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.jboss.resteasy.reactive.RestStreamElementType;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.BROWSER_EVENT;

@Path("/sse")
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

    /*
    @Channel(READ_BROWSER_EVENT)
    Multi<String> aggregateUUID;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> stream() {
        System.out.println("SSEResource.stream");
        return aggregateUUID;
    }

     */
}
