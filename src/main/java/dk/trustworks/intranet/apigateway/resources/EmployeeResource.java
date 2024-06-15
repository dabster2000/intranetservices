package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.userservice.model.Employee;
import jakarta.annotation.security.RolesAllowed;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.List;

import static dk.trustworks.intranet.userservice.model.enums.StatusType.PREBOARDING;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.TERMINATED;

@Tag(name = "employee")
@JBossLog
@Path("/employees")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class EmployeeResource {

    @GET
    public List<Employee> listAll() {
        return Employee.listAll();
    }

    @GET
    @Path("/{uuid}")
    public Employee findById(@PathParam("uuid") String uuid) {
        return Employee.findById(uuid);
    }

    @GET
    @Path("/employed")
    public List<Employee> getAllEmployedEmployees() {
        return Employee.list("status not like ?1 and status not like ?2", TERMINATED, PREBOARDING);
    }

    @GET
    @Path("/incoming")
    public List<Employee> getAllIncomingEmployees() {
        return Employee.list("status like ?1", PREBOARDING);
    }
}
