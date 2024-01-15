package dk.trustworks.intranet.userservice.network;

import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

@RegisterRestClient
@Produces("application/json")
@Consumes("application/json")
@Path("/communications/mails")
public interface MailAPI {

    @POST
    void sendMail(TrustworksMail trustworksMail);

}
