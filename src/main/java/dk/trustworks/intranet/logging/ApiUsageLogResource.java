package dk.trustworks.intranet.logging;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;

@JBossLog
@Path("/api-usage-logs")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
public class ApiUsageLogResource {

    @Inject
    ApiUsageLogService service;

    @GET
    public List<ApiUsageLog> list(@QueryParam("date") LocalDate date,
                                  @QueryParam("path") String path,
                                  @QueryParam("user") String user) {
        log.debug("ApiUsageLogResource.list date=" + date + " path=" + path + " user=" + user);
        return service.search(date, path, user);
    }

    @GET
    @Path("/count")
    public long count(@QueryParam("path") String path,
                      @QueryParam("date") LocalDate date) {
        log.debug("ApiUsageLogResource.count path=" + path + " date=" + date);
        if(date != null) return service.countByPathAndDay(path, date);
        return service.countByPath(path);
    }

    @GET
    @Path("/performance")
    public ApiUsageLogService.Stats performance(@QueryParam("path") String path,
                                                @QueryParam("date") LocalDate date) {
        log.debug("ApiUsageLogResource.performance path=" + path + " date=" + date);
        return service.performanceStats(path, date);
    }
}
