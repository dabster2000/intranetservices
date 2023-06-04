package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.ItExpenseCategory;
import dk.trustworks.intranet.services.ItExpenseCategoryService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.List;

@JBossLog
@ApplicationScoped
@Path("/itexpense/category")
@RolesAllowed({"SYSTEM", "USER"})
@SecurityRequirement(name = "jwt")
public class ItExpenseCategoryResource {

    @Inject
    ItExpenseCategoryService service;

    @GET
    public List<ItExpenseCategory> findAll() {
        return service.findAll();
    }
}
