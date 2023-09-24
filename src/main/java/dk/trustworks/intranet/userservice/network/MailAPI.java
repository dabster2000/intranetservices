package dk.trustworks.intranet.userservice.network;

import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

@RegisterRestClient
@Produces("application/json")
@Consumes("application/json")
@Path("/communications/mails")
public interface MailAPI {

    @POST
    void sendMail(TrustworksMail trustworksMail);

}
