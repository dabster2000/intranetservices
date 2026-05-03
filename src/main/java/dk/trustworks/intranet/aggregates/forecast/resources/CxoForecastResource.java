package dk.trustworks.intranet.aggregates.forecast.resources;

import dk.trustworks.intranet.aggregates.forecast.dto.cxo.CapacityDemandMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.ContractRunoffMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.PipelineHealthMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.RevenueForecastBandMonthDTO;
import dk.trustworks.intranet.aggregates.forecast.dto.cxo.WinRateStageDTO;
import dk.trustworks.intranet.aggregates.forecast.services.CxoForecastService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST API for CxO Command Center forecast metrics — contract runoff, win rates,
 * pipeline health, capacity-demand, and revenue forecast. Class-level scope
 * inherits to all endpoint methods.
 */
@JBossLog
@Tag(name = "forecast")
@Path("/forecast/cxo")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class CxoForecastResource {

    @Inject
    CxoForecastService cxoForecastService;

    static Set<String> parseCommaSeparated(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Set<String> out = new HashSet<>();
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out.isEmpty() ? null : out;
    }

    @GET
    @Path("/contract-runoff")
    public List<ContractRunoffMonthDTO> contractRunoff(@QueryParam("companyIds") String companyIds) {
        return cxoForecastService.contractRunoff(parseCommaSeparated(companyIds));
    }

    @GET
    @Path("/win-rates")
    public List<WinRateStageDTO> winRates(@QueryParam("companyIds") String companyIds) {
        return cxoForecastService.winRates(parseCommaSeparated(companyIds));
    }

    @GET
    @Path("/pipeline-health")
    public List<PipelineHealthMonthDTO> pipelineHealth(@QueryParam("companyIds") String companyIds) {
        return cxoForecastService.pipelineHealth(parseCommaSeparated(companyIds));
    }

    @GET
    @Path("/revenue-forecast")
    public List<RevenueForecastBandMonthDTO> revenueForecast(@QueryParam("companyIds") String companyIds) {
        return cxoForecastService.revenueForecast(parseCommaSeparated(companyIds));
    }

    @GET
    @Path("/capacity-demand")
    public List<CapacityDemandMonthDTO> capacityDemand(@QueryParam("companyIds") String companyIds) {
        return cxoForecastService.capacityDemand(parseCommaSeparated(companyIds));
    }
}
