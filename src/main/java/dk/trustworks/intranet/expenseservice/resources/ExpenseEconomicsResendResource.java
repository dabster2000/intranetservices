package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseRelinkRequestDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseRelinkResultDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseResendPrecheckDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseResendRequestDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseResendResultDTO;
import dk.trustworks.intranet.expenseservice.services.ExpenseEconomicRelinkService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    @Inject ExpenseEconomicRelinkService relink;
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

    /**
     * Rows whose voucher is still in e-conomic are re-sent only when the request carries
     * {@code confirmDuplicates}; without it they come back as {@code skipped} with the reason,
     * so an unconfirmed bulk re-send cannot duplicate a booked voucher.
     */
    @POST
    @Path("/resend")
    @RolesAllowed({"expenses:review"})
    public ExpenseResendResultDTO resend(@Valid ExpenseResendRequestDTO body) {
        String actor = header.getUserUuid();
        boolean confirmDuplicates = body.duplicatesConfirmed();
        int updated = 0;
        List<ExpenseResendResultDTO.Skipped> skipped = new ArrayList<>();
        List<ExpenseResendResultDTO.Failed> failed = new ArrayList<>();
        for (String uuid : body.uuids()) {
            try {
                resend.resendOne(uuid, actor, confirmDuplicates);
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

    /**
     * Re-link each expense to an existing (moved/renumbered) voucher. Rows are independent —
     * one row's failure never blocks the rest — but a request containing the same expense or
     * the same target voucher twice is rejected outright: the second write would either be a
     * confusing no-op or a double-claim, so the payload itself must be unambiguous.
     */
    @POST
    @Path("/relink")
    @RolesAllowed({"expenses:review"})
    public ExpenseRelinkResultDTO relink(@Valid ExpenseRelinkRequestDTO body) {
        rejectDuplicates(body);
        String actor = header.getUserUuid();
        List<ExpenseRelinkResultDTO.Applied> applied = new ArrayList<>();
        List<ExpenseRelinkResultDTO.Skipped> skipped = new ArrayList<>();
        List<ExpenseRelinkResultDTO.Failed> failed = new ArrayList<>();
        for (ExpenseRelinkRequestDTO.Row row : body.rows()) {
            try {
                applied.add(relink.relinkOne(row, actor, body.dryRun()));
            } catch (NotFoundException ex) {
                skipped.add(new ExpenseRelinkResultDTO.Skipped(row.uuid(), "not found"));
            } catch (BadRequestException ex) {
                skipped.add(new ExpenseRelinkResultDTO.Skipped(row.uuid(), ex.getMessage()));
            } catch (Exception ex) {
                log.errorf(ex, "Expense e-conomic re-link failed for uuid=%s", row.uuid());
                failed.add(new ExpenseRelinkResultDTO.Failed(row.uuid(), "re-link failed"));
            }
        }
        return new ExpenseRelinkResultDTO(body.dryRun(), applied.size(), applied, skipped, failed);
    }

    static void rejectDuplicates(ExpenseRelinkRequestDTO body) {
        Set<String> uuids = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (ExpenseRelinkRequestDTO.Row row : body.rows()) {
            if (!uuids.add(row.uuid())) {
                throw new BadRequestException("duplicate expense uuid in request: " + row.uuid());
            }
            String key = row.target() == ExpenseRelinkRequestDTO.Target.BOOKED
                    ? "booked:" + row.accountingyear() + ":" + row.vouchernumber()
                    : "draft:" + row.journalnumber() + ":" + row.accountingyear() + ":" + row.vouchernumber();
            if (!targets.add(key)) {
                throw new BadRequestException("duplicate target voucher in request: " + key);
            }
        }
    }
}
