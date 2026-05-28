package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewApproveDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewRejectDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewSendBackDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseDecisionLogService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Path("/expenses/{uuid}/review")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExpenseReviewDecisionResource {

    @Inject ExpenseDecisionLogService logs;
    @Inject RequestHeaderHolder header;

    @POST
    @Path("/approve")
    @RolesAllowed({"expenses:review"})
    @Transactional
    public Response approve(@PathParam("uuid") String uuid,
                            @Valid ExpenseReviewApproveDTO body) {
        Expense e = Expense.findById(uuid);
        requireOverridable(e);
        logs.recordHRApprove(e, header.getUserUuid(), body.reason());
        e.setStatus("VALIDATED");
        e.setReviewState(null);
        e.setHrDecision("APPROVED");
        e.setHrDecisionBy(header.getUserUuid());
        e.setHrDecisionAt(LocalDateTime.now());
        e.setDatemodified(LocalDate.now());
        return Response.noContent().build();
    }

    @POST
    @Path("/send-back")
    @RolesAllowed({"expenses:review"})
    @Transactional
    public Response sendBack(@PathParam("uuid") String uuid,
                             @Valid ExpenseReviewSendBackDTO body) {
        Expense e = Expense.findById(uuid);
        if (e == null) throw new NotFoundException();
        if (!"PENDING_HR".equals(e.getReviewState()))
            throw new BadRequestException("send-back requires PENDING_HR");
        logs.recordHRSendBack(e, header.getUserUuid(), body.comment());
        e.setReviewState("HR_SENT_BACK");
        e.setHrComment(body.comment());
        e.setHrDecision("SENT_BACK");
        e.setHrDecisionBy(header.getUserUuid());
        e.setHrDecisionAt(LocalDateTime.now());
        e.setDatemodified(LocalDate.now());
        return Response.noContent().build();
    }

    @POST
    @Path("/reject")
    @RolesAllowed({"expenses:review"})
    @Transactional
    public Response reject(@PathParam("uuid") String uuid,
                           @Valid ExpenseReviewRejectDTO body) {
        Expense e = Expense.findById(uuid);
        requirePendingOrSentBack(e);
        logs.recordHRReject(e, header.getUserUuid(), body.reason());
        e.setStatus("DELETED");
        e.setReviewState(null);
        e.setHrComment(body.reason());
        e.setHrDecision("REJECTED");
        e.setHrDecisionBy(header.getUserUuid());
        e.setHrDecisionAt(LocalDateTime.now());
        e.setDatemodified(LocalDate.now());
        return Response.noContent().build();
    }

    private void requirePendingOrSentBack(Expense e) {
        if (e == null) throw new NotFoundException();
        if (!List.of("PENDING_HR", "HR_SENT_BACK").contains(e.getReviewState()))
            throw new BadRequestException(
                "decision requires reviewState in (PENDING_HR, HR_SENT_BACK)");
    }

    private void requireOverridable(Expense e) {
        if (e == null) throw new NotFoundException();
        if (!List.of("PENDING_HR", "HR_SENT_BACK", "NEEDS_FIX", "NEEDS_JUSTIFICATION")
                .contains(e.getReviewState()))
            throw new BadRequestException(
                "approve requires reviewState in (PENDING_HR, HR_SENT_BACK, NEEDS_FIX, NEEDS_JUSTIFICATION)");
    }
}
