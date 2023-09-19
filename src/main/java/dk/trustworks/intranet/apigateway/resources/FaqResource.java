package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.knowledgeservice.model.Faq;
import dk.trustworks.intranet.knowledgeservice.services.FaqService;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import java.util.List;
import java.util.UUID;

@Tag(name = "faq")
@Path("/faq")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class FaqResource {


    private final FaqService faqService;

    @Inject
    public FaqResource(FaqService faqService) {
        this.faqService = faqService;
    }

    @GET
    public List<Faq> findAll() {
        return faqService.findAll();
    }

    @POST
    @Transactional
    public void create(Faq faq) {
        if(faq.getUuid() == null || faq.getUuid().isEmpty()) faq.setUuid(UUID.randomUUID().toString());
        faqService.create(faq);
    }

    @PUT
    @Transactional
    public void update(Faq faq) {
        faqService.update(faq);
    }

    @DELETE
    @Path("/{uuid}")
    @Transactional
    public void delete(@PathParam("uuid") String uuid) {
        faqService.delete(uuid);
    }

}
