package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.ItExpenseCategory;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeEnforced;
import dk.trustworks.intranet.services.ItExpenseCategoryService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * IT equipment types ({@code itbudget_category}). The {@code lifespan} on a
 * type is the amortization length in months: how long an item of that type
 * keeps consuming its owner's IT budget before it stops counting.
 * <p>
 * Reads are open to every {@code devices:read} holder — the profile page needs
 * them to render an equipment card. Writes are an administrative act on a
 * company-wide policy, so they take {@code devices:write}, are
 * {@link ScopeEnforced} — the same {@code devices:write} an employee holds to
 * register their own laptop must not also let them re-price everyone's
 * amortization — and are logged with the acting user from
 * {@code X-Requested-By}. Mutation bodies are a record DTO so the entity is
 * never mass-assigned from the wire.
 */
@JBossLog
@RequestScoped
@Path("/itexpense/category")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"devices:read"})
@SecurityRequirement(name = "jwt")
public class ItExpenseCategoryResource {

    @Inject
    ItExpenseCategoryService service;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * Create/update body. {@code lifespan} is the amortization length in months.
     * A record rather than the entity: a client must not be able to set the id
     * or reach any field the service does not explicitly accept.
     */
    public record EquipmentTypeRequest(String name, String longName, Integer lifespan, String description) {
        int lifespanOrZero() {
            return lifespan == null ? 0 : lifespan;
        }
    }

    @GET
    public List<ItExpenseCategory> findAll() {
        return service.findAll();
    }

    @POST
    @RolesAllowed({"devices:write"})
    @ScopeEnforced
    public ItExpenseCategory create(EquipmentTypeRequest request) {
        ItExpenseCategory created = service.createCategory(
                nameOf(request), longNameOf(request), lifespanOf(request), descriptionOf(request));
        log.infof("ItExpenseCategoryResource.create id=%d name=%s lifespan=%d updatedBy=%s",
                created.getId(), logSafe(created.getName()), created.getLifespan(),
                requestHeaderHolder.getUserUuid());
        return created;
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({"devices:write"})
    @ScopeEnforced
    public ItExpenseCategory update(@PathParam("id") int id, EquipmentTypeRequest request) {
        ItExpenseCategory updated = service.updateCategory(
                id, nameOf(request), longNameOf(request), lifespanOf(request), descriptionOf(request));
        log.infof("ItExpenseCategoryResource.update id=%d name=%s lifespan=%d updatedBy=%s",
                id, logSafe(updated.getName()), updated.getLifespan(), requestHeaderHolder.getUserUuid());
        return updated;
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed({"devices:write"})
    @ScopeEnforced
    public void delete(@PathParam("id") int id) {
        service.deleteCategory(id);
        log.infof("ItExpenseCategoryResource.delete id=%d updatedBy=%s", id, requestHeaderHolder.getUserUuid());
    }

    private static String nameOf(EquipmentTypeRequest request) {
        return request == null ? null : request.name();
    }

    private static String longNameOf(EquipmentTypeRequest request) {
        return request == null ? null : request.longName();
    }

    private static String descriptionOf(EquipmentTypeRequest request) {
        return request == null ? null : request.description();
    }

    private static int lifespanOf(EquipmentTypeRequest request) {
        return request == null ? 0 : request.lifespanOrZero();
    }

    /**
     * Flattens CR/LF out of a free-text value bound for the log — a
     * caller-supplied newline would otherwise render as a second, forged log
     * event under the awslogs driver.
     */
    private static String logSafe(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n]+", " ");
    }
}
