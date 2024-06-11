package dk.trustworks.intranet.ai;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/ai")
@ApplicationScoped
public class AiRessource {

    @Inject
    MyAiService myAiService;

    @GET
    public String writeAPoem() {
        return myAiService.writeAPoem("love", 4);
    }
}
