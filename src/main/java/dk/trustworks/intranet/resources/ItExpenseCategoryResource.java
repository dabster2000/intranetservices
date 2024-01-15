package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.ItExpenseCategory;
import dk.trustworks.intranet.services.ItExpenseCategoryService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;

@JBossLog
@ApplicationScoped
@Path("/itexpense/category")
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class ItExpenseCategoryResource {

    @Inject
    ItExpenseCategoryService service;

    @GET
    public List<ItExpenseCategory> findAll() {
        return service.findAll();
    }
}
