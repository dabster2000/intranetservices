package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.dto.ExpenseFile;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.expenseservice.dto.CreateExpenseDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseDecisionLogEntryDTO;
import dk.trustworks.intranet.expenseservice.dto.ExpenseJustificationDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseDeletePolicy;
import dk.trustworks.intranet.expenseservice.model.ExpenseCategory;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import dk.trustworks.intranet.expenseservice.services.ExpenseClassificationService;
import dk.trustworks.intranet.expenseservice.services.ExpenseDecisionLogService;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileNotFoundException;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.model.Company;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.jboss.resteasy.annotations.jaxrs.PathParam;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@JBossLog
@Path("/expenses")
@RequestScoped
@Produces("application/json")
@Consumes("application/json")
@RolesAllowed({"expenses:read"})
public class ExpenseResource {
    private static final String STATUS_DELETED = "DELETED";

    @Inject
    ExpenseService expenseService;

    @Inject
    UserService userService;

    @Inject
    ExpenseFileService expenseFileService;

    @Inject
    EntityManager em;

    @Inject
    ExpenseDecisionLogService logs;

    @Inject
    ExpenseClassificationService classificationService;

    @Inject
    dk.trustworks.intranet.security.RequestHeaderHolder header;

    @Inject
    SecurityIdentity identity;

    @GET
    @Path("/{uuid}")
    public Expense findByUuid(@PathParam("uuid") String uuid) {
        return Expense.findById(uuid);
    }

    @GET
    @Path("/{uuid}/decision-log")
    @RolesAllowed({"expenses:read", "expenses:review"})
    public java.util.List<ExpenseDecisionLogEntryDTO> decisionLog(@PathParam("uuid") String uuid) {
        Expense e = Expense.findById(uuid);
        if (e == null) throw new NotFoundException();

        String caller = header.getUserUuid();
        boolean isHR = identity.hasRole("expenses:review");
        boolean isOwner = caller != null && caller.equals(e.getUseruuid());
        if (!isHR && !isOwner) throw new ForbiddenException();

        return logs.findByExpense(uuid).stream().map(l ->
            new ExpenseDecisionLogEntryDTO(
                l.uuid,
                l.occurredAt != null ? l.occurredAt.atOffset(java.time.ZoneOffset.UTC) : null,
                l.actorRole, l.actorUuid,
                l.actorUuid != null ? lookupActorName(l.actorUuid) : null,
                l.action, l.reasonText,
                l.fromReviewState, l.toReviewState, l.aiRuleId)
        ).toList();
    }

    private String lookupActorName(String actorUuid) {
        User u = User.findById(actorUuid);
        return u == null ? null : u.getFirstname() + " " + u.getLastname();
    }

    @GET
    @Path("/file/{uuid}")
    public ExpenseFile getFileById(@PathParam("uuid") String uuid) {
        try {
            return expenseFileService.getFileById(uuid);
        } catch (ExpenseFileNotFoundException e) {
            throw new NotFoundException(e.getMessage(), e);
        }
    }

    /**
     * Validates an expense receipt using OpenAI vision API.
     * Returns a short validation message about receipt readability and completeness.
     * Restricted to ADMIN, HR, and SYSTEM roles.
     *
     * @param uuid UUID of the expense to validate
     * @return KeyValueDTO with expense UUID as key and validation message as value
     */
    @GET
    @Path("/{uuid}/validate")
    @RolesAllowed({"expenses:read"})
    public KeyValueDTO validateExpense(@PathParam("uuid") String uuid) {
        log.infof("Validating expense receipt via REST API for uuid=%s", uuid);
        String validationMessage = expenseService.validateExpenseReceipt(uuid);
        return new KeyValueDTO(uuid, validationMessage);
    }

    @GET
    @Path("/user/{useruuid}")
    public List<Expense> findByUser(@PathParam("useruuid") String useruuid,
                                     @QueryParam("limit") String limit,
                                     @QueryParam("page") String page,
                                     @QueryParam("includeDeleted") @DefaultValue("false") boolean includeDeleted) {
        int pageInt = Integer.parseInt(page);
        int limitInt = Integer.parseInt(limit);

        return Expense.find("useruuid = ?1 and status <> ?2", Sort.by("expensedate").descending(), useruuid, STATUS_DELETED)
                .page(Page.of(pageInt, limitInt))
                .list();
    }

    public List<Expense> findByUser(@PathParam("useruuid") String useruuid) {
        return Expense.find("useruuid = ?1 and status <> ?2", useruuid, STATUS_DELETED).list();
    }

    @GET
    @Path("/project/{projectuuid}/search/period")
    public List<Expense> findByProjectAndPeriod(@PathParam("projectuuid") String projectuuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate localFromDate = LocalDate.parse(fromdate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LocalDate localToDate = LocalDate.parse(todate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return Expense.find("projectuuid like ?1 and expensedate >= ?2 and expensedate <= ?3 and status <> ?4",
                projectuuid, localFromDate, localToDate, STATUS_DELETED).list();
    }

    @GET
    @Path("/user/{useruuid}/search/period")
    public List<Expense> findByUserAndPeriod(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate localFromDate = LocalDate.parse(fromdate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LocalDate localToDate = LocalDate.parse(todate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return Expense.find("useruuid like ?1 and expensedate >= ?2 and expensedate <= ?3 and status <> ?4",
                useruuid, localFromDate, localToDate, STATUS_DELETED).list();
    }

    @GET
    @Path("/search/period")
    public List<Expense> findByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate localFromDate = LocalDate.parse(fromdate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LocalDate localToDate = LocalDate.parse(todate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return Expense.find("expensedate >= ?1 and expensedate <= ?2 and status <> ?3",
            localFromDate,
            localToDate,
            STATUS_DELETED).list();
    }

    @GET
    @Path("/search/statuses")
    public List<Expense> findByStatuses(@QueryParam("statuses") String statusesParam) {
        if (statusesParam == null || statusesParam.isEmpty()) {
            return List.of();
        }
        String[] statuses = statusesParam.split(",");
        if (statuses.length > 20) {
            throw new BadRequestException("too many status values (max 20)");
        }
        StringBuilder queryBuilder = new StringBuilder("status IN (");
        for (int i = 0; i < statuses.length; i++) {
            queryBuilder.append("?").append(i + 1);
            if (i < statuses.length - 1) {
                queryBuilder.append(", ");
            }
        }
        queryBuilder.append(") and status <> ?").append(statuses.length + 1);

        Object[] params = new Object[statuses.length + 1];
        System.arraycopy(statuses, 0, params, 0, statuses.length);
        params[statuses.length] = STATUS_DELETED;

        return Expense.find(queryBuilder.toString(), Sort.by("datecreated").descending(), params).list();
    }

    @GET
    @Path("/categories")
    public List<ExpenseCategory> getCategories(@QueryParam("useruuid") String useruuid) {
        User user = userService.findById(useruuid, false);
        Company company = userService.getUserStatus(user, LocalDate.now()).getCompany();

        int mostFrequentAccount = findMostFrequentAccount();

        List<ExpenseCategory> expenseCategories = ExpenseCategory.listAll();
        expenseCategories.forEach(expenseCategory -> expenseCategory.getExpenseAccounts().removeIf(expenseAccount -> !expenseAccount.isActive() || !expenseAccount.getCompanyuuid().equals(company.getUuid())));
        return expenseCategories
                .stream()
                .filter(expenseCategory -> expenseCategory.isActive() && !expenseCategory.getExpenseAccounts().isEmpty())
                .peek(expenseCategory -> expenseCategory.getExpenseAccounts().forEach(expenseAccount -> expenseAccount.setDefaultAccount(expenseAccount.getAccountNumber() == mostFrequentAccount)))
                .toList();
    }

    @Transactional
    public int findMostFrequentAccount() {
        Query query = em.createQuery("SELECT e.account, COUNT(e) AS occurrences FROM Expense e GROUP BY e.account ORDER BY occurrences DESC");
        query.setMaxResults(1);
        Object result = query.getSingleResult();
        return Integer.parseInt(result != null ? (String) ((Object[]) result)[0] : null); // Cast and return the account
    }

    @POST
    @RolesAllowed({"expenses:write"})
    @Transactional
    public Response saveExpense(@Valid CreateExpenseDTO dto) throws IOException {
        log.info("ExpenseResource.saveExpense");
        // Map the client-writable request DTO onto a fresh entity. Server-managed fields
        // (status/state/AI verdict/voucher triple/version/…) are absent from the DTO, so
        // they are structurally unbindable; processExpense still owns the workflow head.
        Expense expense = new Expense();
        expense.setUseruuid(dto.getUseruuid());
        expense.setAmount(dto.getAmount());
        expense.setAccount(dto.getAccount());
        expense.setAccountname(dto.getAccountname());
        if (dto.getDescription() != null) expense.setDescription(dto.getDescription());
        expense.setAccountantNotes(dto.getAccountantNotes());
        if (dto.getProjectuuid() != null) expense.setProjectuuid(dto.getProjectuuid());
        if (dto.getDatecreated() != null) expense.setDatecreated(dto.getDatecreated());
        expense.setExpensedate(dto.getExpensedate());
        expense.setCustomerexpense(dto.isCustomerexpense());
        expense.setExpensefile(dto.getExpensefile());
        expense.setClassification(dto.getClassification());
        log.info("expense = " + expense);
        classificationService.applyResolvedAccount(expense);
        expenseService.processExpense(expense, () -> classificationService.persistSubmittedClassification(expense));
        return Response.status(Response.Status.CREATED).entity(expense).build();
    }

    @POST
    @Path("/{uuid}/justification")
    @RolesAllowed({"expenses:write"})
    @Transactional
    public Response submitJustification(@PathParam("uuid") String uuid,
                                        @Valid ExpenseJustificationDTO body) {
        Expense e = Expense.findById(uuid);
        if (e == null) throw new NotFoundException();

        String caller = header.getUserUuid();
        if (caller == null || !caller.equals(e.getUseruuid()))
            throw new ForbiddenException("not the expense owner");

        if (!ExpenseStateDeriver.NEEDS_ATTENTION.equals(e.getState())
                || !ExpenseStateDeriver.OWNER_EMPLOYEE.equals(e.getAttentionOwner())
                || !ExpenseStateDeriver.KIND_JUSTIFICATION.equals(e.getAttentionKind())) {
            throw new BadRequestException("justification requires an employee-owned JUSTIFICATION item");
        }

        // Log BEFORE mutating so fromReviewState is captured correctly.
        logs.recordEmployeeJustification(e, caller, body.justification());

        e.setEmployeeJustification(body.justification());
        // Hand to accounting for a decision.
        e.setState(ExpenseStateDeriver.NEEDS_ATTENTION);
        e.setAttentionOwner(ExpenseStateDeriver.OWNER_ACCOUNTING);
        e.setAttentionKind(ExpenseStateDeriver.KIND_POLICY);
        e.setDatemodified(java.time.LocalDate.now());
        return Response.noContent().build();
    }

    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"expenses:write"})
    @Transactional
    public void updateOne(@PathParam("uuid") String uuid, Expense expense) {
        Expense existing = Expense.findById(uuid);
        if (existing == null) {
            throw new WebApplicationException("Expense not found", 404);
        }

        if (expense.getClassification() != null) {
            expense.setUseruuid(existing.getUseruuid());
            classificationService.applyResolvedAccount(expense);
        }

        // Build dynamic update query based on what's being updated
        StringBuilder updateQuery = new StringBuilder();
        List<Object> params = new java.util.ArrayList<>();

        if (expense.getAmount() != null) {
            updateQuery.append("amount = ?").append(params.size() + 1).append(", ");
            params.add(expense.getAmount());
        }
        if (expense.getAccount() != null) {
            updateQuery.append("account = ?").append(params.size() + 1).append(", ");
            params.add(expense.getAccount());
        }
        if (expense.getAccountname() != null) {
            updateQuery.append("accountname = ?").append(params.size() + 1).append(", ");
            params.add(expense.getAccountname());
        }
        if (expense.getDescription() != null) {
            updateQuery.append("description = ?").append(params.size() + 1).append(", ");
            params.add(expense.getDescription());
        }
        if (expense.getProjectuuid() != null) {
            updateQuery.append("projectuuid = ?").append(params.size() + 1).append(", ");
            params.add(expense.getProjectuuid());
        }
        if (expense.getExpensedate() != null) {
            updateQuery.append("expensedate = ?").append(params.size() + 1).append(", ");
            params.add(expense.getExpensedate());
        }

        if (!updateQuery.isEmpty()) {
            // Remove trailing comma and space
            updateQuery.setLength(updateQuery.length() - 2);
            updateQuery.append(" WHERE uuid = ?").append(params.size() + 1);
            params.add(uuid);

            Expense.update(updateQuery.toString(), params.toArray());
        }

        // Receipt replacement: when the client sends a new base64 file, overwrite
        // the existing S3 object under the same uuid key.
        if (expense.getExpensefile() != null && !expense.getExpensefile().isEmpty()) {
            ExpenseFile newFile = new ExpenseFile(uuid, expense.getExpensefile());
            expenseFileService.saveFile(newFile);
        }

        if (expense.getClassification() != null) {
            existing.setClassification(expense.getClassification());
            existing.setAccount(expense.getAccount());
            existing.setAccountname(expense.getAccountname());
            classificationService.persistSubmittedClassification(existing);
        }

        // If the row is sitting in a review state waiting on the employee, this edit
        // counts as a fix attempt: clear the review flags, log the edit, and re-fire
        // AI validation. No-op for any other review_state.
        expenseService.maybeReopenForRevalidation(uuid, header.getUserUuid());
    }

    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"expenses:write"})
    @Transactional
    public void delete(@PathParam("uuid") String uuid) {
        log.info("Deleting expense with uuid: "+ uuid);
        Expense expense = Expense.findById(uuid);
        if (expense == null) {
            throw new WebApplicationException("Expense not found", 404);
        }

        String actorUuid = header.getUserUuid();
        boolean isAccountingReviewer = identity.hasRole("expenses:review");
        if (!isAccountingReviewer && (actorUuid == null || !actorUuid.equals(expense.getUseruuid()))) {
            throw new ForbiddenException("not the expense owner");
        }

        String blockedReason = ExpenseDeletePolicy.blockedReason(expense);
        if (blockedReason != null) {
            log.warnf("Cannot delete expense %s: %s status=%s, journal=%s, voucher=%d, year=%s",
                    uuid, blockedReason, expense.getStatus(), expense.getJournalnumber(),
                    expense.getVouchernumber(), expense.getAccountingyear());
            throw new BadRequestException(blockedReason);
        }

        String actorRole = isAccountingReviewer ? "ACCOUNTING" : "EMPLOYEE";
        logs.recordExpenseDeleted(expense, actorUuid, actorRole, "Deleted before e-conomic upload");
        markDeleted(expense);
        log.infof("Expense %s deleted successfully", uuid);
    }

    private void markDeleted(Expense expense) {
        expense.setStatus(STATUS_DELETED);
        expense.setState(ExpenseStateDeriver.DELETED);   // authoritative terminal (employee/e-conomic delete)
        expense.setAttentionOwner(null);
        expense.setAttentionKind(null);
        expense.setDatemodified(LocalDate.now());
    }

}
