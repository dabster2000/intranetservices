package dk.trustworks.intranet.userservice.network;

import dk.trustworks.intranet.dto.TrustworksMail;
import io.micrometer.core.annotation.Timed;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

@RegisterRestClient
@Timed(histogram = true)
@Produces("application/json")
@Consumes("application/json")
@Path("/communications/mails")
public interface MailAPI {

    @POST
    void sendMail(TrustworksMail trustworksMail);

}
