package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseResendPrecheckDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseResendRequestDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseResendResultDTO;
import dk.trustworks.intranet.expenseservice.services.ExpenseEconomicResendService;
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
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual e-conomic re-send (spec §4). Mirrors {@link ExpenseBatchDecisionResource}: each uuid is
 * processed in its own transaction by the service; ineligible rows are skipped (with a reason) and
 * e-conomic post errors are reported as failed — neither changes the expense's status.
 */
@Path("/expenses/economics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@JBossLog
public class ExpenseEconomicsResendResource {

    @Inject ExpenseEconomicResendService resend;
    @Inject RequestHeaderHolder header;

    @POST
    @Path("/resend/precheck")
    @RolesAllowed({"expenses:review"})
    public List<ExpenseResendPrecheckDTO> precheck(@Valid ExpenseResendRequestDTO body) {
        List<ExpenseResendPrecheckDTO> out = new ArrayList<>();
        for (String uuid : body.uuids()) {
            out.add(resend.precheckOne(uuid));
        }
        return out;
    }

    @POST
    @Path("/resend")
    @RolesAllowed({"expenses:review"})
    public ExpenseResendResultDTO resend(@Valid ExpenseResendRequestDTO body) {
        String actor = header.getUserUuid();
        int updated = 0;
        List<ExpenseResendResultDTO.Skipped> skipped = new ArrayList<>();
        List<ExpenseResendResultDTO.Failed> failed = new ArrayList<>();
        for (String uuid : body.uuids()) {
            try {
                resend.resendOne(uuid, actor);
                updated++;
            } catch (NotFoundException ex) {
                skipped.add(new ExpenseResendResultDTO.Skipped(uuid, "not found"));
            } catch (BadRequestException ex) {
                skipped.add(new ExpenseResendResultDTO.Skipped(uuid, ex.getMessage()));
            } catch (Exception ex) {
                log.errorf(ex, "Expense e-conomic re-send failed for uuid=%s", uuid);
                failed.add(new ExpenseResendResultDTO.Failed(uuid, "re-send failed"));
            }
        }
        return new ExpenseResendResultDTO(updated, skipped, failed);
    }
}
