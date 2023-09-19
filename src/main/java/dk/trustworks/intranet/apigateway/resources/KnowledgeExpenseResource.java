package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.knowledgeservice.model.CKOExpense;
import dk.trustworks.intranet.knowledgeservice.services.CkoExpenseService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.util.List;

@JBossLog
@ApplicationScoped
@Tag(name = "Knowledge")
@Path("/knowledge/expenses")
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class KnowledgeExpenseResource {

    @Inject
    CkoExpenseService knowledgeExpenseAPI;

    @GET
    //@CacheResult(cacheName = "knowledge-cache")
    public List<CKOExpense> findAll() {
        return knowledgeExpenseAPI.findAll();
    }

    @GET
    @Path("/search/findByDescription")
    //@CacheResult(cacheName = "knowledge-cache")
    public List<CKOExpense> findByDescription(@QueryParam("description") String description) {
        return knowledgeExpenseAPI.findByDescription(description);
    }

    @POST
    //@CacheInvalidateAll(cacheName = "knowledge-cache")
    public void saveExpense(CKOExpense ckoExpense) {
        knowledgeExpenseAPI.saveExpense(ckoExpense);
    }

    @PUT
    //@CacheInvalidateAll(cacheName = "knowledge-cache")
    public void updateExpense(CKOExpense ckoExpense) {
        knowledgeExpenseAPI.updateExpense(ckoExpense);
    }

    @DELETE
    @Path("/{uuid}")
    //@CacheInvalidateAll(cacheName = "knowledge-cache")
    public void deleteExpense(@PathParam("uuid") String uuid) {
        knowledgeExpenseAPI.deleteExpense(uuid);
    }
}
