package dk.trustworks.intranet.aggregates;

import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;
import org.jboss.resteasy.reactive.RestStreamElementType;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.BROWSER_EVENT;

@Path("/sse")
@ApplicationScoped
@JBossLog
public class SSEResource {

    private final BroadcastProcessor<String> broadcastProcessor = BroadcastProcessor.create();

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    //@SseElementType(MediaType.TEXT_PLAIN)
    public Multi<String> stream() {
        log.info("SSEResource.stream");
        return broadcastProcessor;
    }

    @ConsumeEvent(BROWSER_EVENT)
    public void consume(String aggregateUUID) {
        log.infof("SSEResource.consume: %s", aggregateUUID);
        broadcastProcessor.onNext(aggregateUUID);
    }

}
