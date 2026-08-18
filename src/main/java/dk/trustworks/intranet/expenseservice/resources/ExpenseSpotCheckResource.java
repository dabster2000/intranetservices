package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewListItemDTO;
import dk.trustworks.intranet.expenseservice.services.ExpenseSpotCheckService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * W5: the weekly spot-check over auto-cleared items. GET lists the deterministic
 * sample; POST records the verdict (CLEAR / REJECT — a posted item degrades to an
 * audited follow-up flag); /digest is the per-employee summary view.
 */
@Path("/expenses/review/spot-check")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"expenses:review"})
public class ExpenseSpotCheckResource {

    @Inject ExpenseSpotCheckService spotCheck;
    @Inject ExpenseReviewResource reviewResource;
    @Inject RequestHeaderHolder header;

    @GET
    public List<ExpenseReviewListItemDTO> sample() {
        return spotCheck.sample().stream().map(reviewResource::toDTO).toList();
    }

    public record SpotCheckDecisionDTO(
            @NotNull @Pattern(regexp = "CLEAR|REJECT", message = "verdict must be CLEAR or REJECT")
            String verdict,
            String reason) {}

    public record SpotCheckOutcomeDTO(String outcome) {}

    @POST
    @Path("/{uuid}")
    public SpotCheckOutcomeDTO decide(@PathParam("uuid") String uuid,
                                      @Valid SpotCheckDecisionDTO body) {
        ExpenseSpotCheckService.Outcome outcome = spotCheck.decide(
                uuid, header.getUserUuid(), "REJECT".equals(body.verdict()), body.reason());
        return new SpotCheckOutcomeDTO(outcome.name());
    }

    public record DigestRowDTO(String useruuid, String employeeName, int autoCleared,
                               double totalAmount, int softFlagged,
                               int aiAcceptedJustifications, int spotCheckFlags) {}

    @GET
    @Path("/digest")
    public List<DigestRowDTO> digest(@QueryParam("days") @DefaultValue("30") int days) {
        return spotCheck.digestRows(days).stream().map(r -> {
            String useruuid = (String) r[0];
            User u = useruuid != null ? User.findById(useruuid) : null;
            String name = u != null ? (u.getFirstname() + " " + u.getLastname()) : null;
            return new DigestRowDTO(useruuid, name,
                    ((Number) r[1]).intValue(),
                    ((Number) r[2]).doubleValue(),
                    ((Number) r[3]).intValue(),
                    ((Number) r[4]).intValue(),
                    ((Number) r[5]).intValue());
        }).toList();
    }
}
