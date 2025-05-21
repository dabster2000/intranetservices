package dk.trustworks.intranet.apis.openai;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/openai")
public class OpenAIResource {
    
    @Inject
    OpenAIService openAIService;
    
    @POST
    @Path("/ask")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public String askQuestion(String question) {
        return openAIService.askQuestion(question);
    }
}