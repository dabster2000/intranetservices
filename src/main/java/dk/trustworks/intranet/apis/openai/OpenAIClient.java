package dk.trustworks.intranet.apis.openai;

import dk.trustworks.intranet.exceptions.OpenAIErrorMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/v1")
@RegisterRestClient(configKey = "openai-api")
@RegisterProvider(OpenAIErrorMapper.class)
public interface OpenAIClient {
    
    @POST
    @Path("/chat/completions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    String createChatCompletion(
            @HeaderParam("Authorization") String authorization,
            @HeaderParam("Content-Type") String contentType,
            String request);
}