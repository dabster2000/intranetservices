package dk.trustworks.intranet.aggregates;

import io.smallrye.mutiny.Multi;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.jboss.resteasy.reactive.RestStreamElementType;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.READ_BROWSER_EVENT;

@Path("/sse")
public class SSEResource {

    @Channel(READ_BROWSER_EVENT)
    Multi<String> aggregateUUID;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> stream() {
        System.out.println("SSEResource.stream");
        return aggregateUUID;
    }
}
