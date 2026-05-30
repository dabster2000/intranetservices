package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewApproveDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewRejectDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewSendBackDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import dk.trustworks.intranet.expenseservice.services.ExpenseDecisionLogService;
import dk.trustworks.intranet.expenseservice.services.ExpenseReviewDecisionService;
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

@Path("/expenses/{uuid}/review")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExpenseReviewDecisionResource {

    @Inject ExpenseDecisionLogService logs;
    @Inject ExpenseReviewDecisionService decisions;
    @Inject RequestHeaderHolder header;

    @POST
    @Path("/approve")
    @RolesAllowed({"expenses:review"})
    public Response approve(@PathParam("uuid") String uuid,
                            @Valid ExpenseReviewApproveDTO body) {
        decisions.approve(uuid, header.getUserUuid(), body.reason());
        return Response.noContent().build();
    }

    @POST
    @Path("/send-back")
    @RolesAllowed({"expenses:review"})
    @Transactional
    public Response sendBack(@PathParam("uuid") String uuid,
                             @Valid ExpenseReviewSendBackDTO body) {
        Expense e = Expense.findById(uuid);
        requireAccountingOwned(e);
        logs.recordHRSendBack(e, header.getUserUuid(), body.comment());
        // Hand the ball back to the employee for a justification.
        e.setState(ExpenseStateDeriver.NEEDS_ATTENTION);
        e.setAttentionOwner(ExpenseStateDeriver.OWNER_EMPLOYEE);
        e.setAttentionKind(ExpenseStateDeriver.KIND_JUSTIFICATION);
        e.setReviewState("HR_SENT_BACK");           // vestigial
        e.setHrComment(body.comment());
        e.setHrDecision("SENT_BACK");               // vestigial
        e.setHrDecisionBy(header.getUserUuid());
        e.setHrDecisionAt(LocalDateTime.now());
        e.setDatemodified(LocalDate.now());
        return Response.noContent().build();
    }

    @POST
    @Path("/reject")
    @RolesAllowed({"expenses:review"})
    public Response reject(@PathParam("uuid") String uuid,
                           @Valid ExpenseReviewRejectDTO body) {
        decisions.reject(uuid, header.getUserUuid(), body.reason());
        return Response.noContent().build();
    }

    /** send-back only makes sense when accounting currently owns the item. */
    private void requireAccountingOwned(Expense e) {
        if (e == null) throw new NotFoundException();
        if (!ExpenseStateDeriver.NEEDS_ATTENTION.equals(e.getState())
                || !ExpenseStateDeriver.OWNER_ACCOUNTING.equals(e.getAttentionOwner()))
            throw new BadRequestException("send-back requires state=NEEDS_ATTENTION owned by ACCOUNTING");
    }
}
