package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseBatchDecisionDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseBatchDecisionResultDTO;
import dk.trustworks.intranet.expenseservice.services.ExpenseReviewDecisionService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;

/**
 * The single bulk override path (spec §9). Applies APPROVE/REJECT to many expenses, reusing
 * {@link ExpenseReviewDecisionService} so each row gets the same state writes + decision-log
 * row as the single endpoints. A uuid not in NEEDS_ATTENTION is skipped (not failed), so a
 * partial batch still applies to the valid rows.
 */
@Path("/expenses/decisions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExpenseBatchDecisionResource {

    @Inject ExpenseReviewDecisionService decisions;
    @Inject RequestHeaderHolder header;

    @POST
    @Path("/batch")
    @RolesAllowed({"expenses:review"})
    public ExpenseBatchDecisionResultDTO batch(@Valid ExpenseBatchDecisionDTO body) {
        boolean approve = "APPROVE".equals(body.decision());
        String actor = header.getUserUuid();
        int updated = 0;
        List<ExpenseBatchDecisionResultDTO.Skipped> skipped = new ArrayList<>();

        for (String uuid : body.uuids()) {
            try {
                // Each decision runs in its own transaction (the service methods are @Transactional),
                // so one bad row does not roll back the rest.
                if (approve) {
                    decisions.approve(uuid, actor, body.reason());
                } else {
                    decisions.reject(uuid, actor, body.reason());
                }
                updated++;
            } catch (NotFoundException ex) {
                skipped.add(new ExpenseBatchDecisionResultDTO.Skipped(uuid, "not found"));
            } catch (BadRequestException ex) {
                skipped.add(new ExpenseBatchDecisionResultDTO.Skipped(uuid, "not awaiting a decision"));
            }
        }
        return new ExpenseBatchDecisionResultDTO(updated, skipped);
    }
}
