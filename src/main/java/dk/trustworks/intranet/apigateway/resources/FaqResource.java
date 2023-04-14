package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.knowledgeservice.model.Faq;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import java.util.List;
import java.util.UUID;

@Tag(name = "faq")
@Path("/faq")
@RequestScoped
@RolesAllowed({"USER", "EXTERNAL"})
@SecurityRequirement(name = "jwt")
public class FaqResource {

    @GET
    public List<Faq> findAll() {
        return Faq.listAll();
    }

    @POST
    @Transactional
    public void create(Faq faq) {
        if(faq.getUuid() == null || faq.getUuid().isEmpty()) faq.setUuid(UUID.randomUUID().toString());
        faq.persist();
    }

    @PUT
    @Transactional
    public void update(Faq faq) {
        Faq.update("faggroup = ?1, title = ?2, content = ?3 WHERE uuid like ?4 ", faq.getFaqgroup(), faq.getTitle(), faq.getContent(), faq.getUuid());
    }

    @DELETE
    @Path("/{uuid}")
    @Transactional
    public void delete(@PathParam("uuid") String uuid) {
        Faq.deleteById(uuid);
    }

}
