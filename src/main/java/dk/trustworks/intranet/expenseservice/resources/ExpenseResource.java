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
import dk.trustworks.intranet.security.ScopeEnforced;
import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.security.ScopeResolution;
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

    @Inject
    ScopeGuard scope;

    @Inject
    dk.trustworks.intranet.expenseservice.services.AIConfigSnapshot aiConfig;

    /** Phase 9.2 denial text — one message for every expense reach refusal. */
    private static final String OUTSIDE_REACH = "This expense is outside your access scope";

    /** W2: employee-readable policy display for the justification form. */
    public record RuleDisplayDTO(String ruleId, String displayName, String sentence) {}

    /**
     * W2: the fired rule's policy sentence with live parameter values (e.g. the actual
     * per-person meal limit) so the employee can address the real threshold in their
     * justification. Read-only, no internal config beyond the rule's own description.
     */
    @GET
    @Path("/rules/{ruleId}/display")
    public RuleDisplayDTO ruleDisplay(@PathParam("ruleId") String ruleId) {
        var rule = aiConfig.getRule(ruleId);
        if (rule == null) throw new WebApplicationException("Rule not found", 404);
        return new RuleDisplayDTO(rule.ruleId(), rule.displayName(),
                aiConfig.renderedRuleDescription(ruleId));
    }

    @GET
    @Path("/{uuid}")
    public Expense findByUuid(@PathParam("uuid") String uuid) {
        Expense expense = expenseService.findByUuid(uuid);
        if (expense != null) {
            // Phase 9.2: the acting human (when present) must reach the owner.
            // OWN covers the employee reading their own expense — including the
            // mobile flow, whose session resolves to the device owner.
            scope.requireSubjectWhenActor("expenses:read", expense.getUseruuid(), OUTSIDE_REACH);
        }
        return expense;
    }

    @GET
    @Path("/{uuid}/decision-log")
    @RolesAllowed({"expenses:read", "expenses:review"})
    public java.util.List<ExpenseDecisionLogEntryDTO> decisionLog(@PathParam("uuid") String uuid) {
        Expense e = expenseService.findByUuid(uuid);
        if (e == null) throw new NotFoundException();

        // Phase 9.2: with an actor, reach decides (owner via OWN, HR/ADMIN via ALL) —
        // the client-credential probe below is meaningless for BFF traffic, whose
        // system token always carries expenses:review regardless of the human.
        if (scope.actorOrNull() != null) {
            scope.requireSubjectWhenActor("expenses:read", e.getUseruuid(), OUTSIDE_REACH);
        } else if (!identity.hasRole("expenses:review")) {
            throw new ForbiddenException();
        }

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
        // Phase 9.2: receipts are the route the phase file calls out — a list can
        // be correctly scoped while the file route serves any receipt by UUID.
        // The file shares the expense's uuid; the acting human must reach the
        // owning expense's owner. No owning row → 404 for humans (fail closed).
        if (scope.actorOrNull() != null) {
            Expense owning = expenseService.findByUuid(uuid);
            if (owning == null) {
                throw new NotFoundException("No expense for receipt " + uuid);
            }
            scope.requireSubjectWhenActor("expenses:read", owning.getUseruuid(), OUTSIDE_REACH);
        }
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
        Expense expense = expenseService.findByUuid(uuid);
        if (expense != null) {
            scope.requireSubjectWhenActor("expenses:read", expense.getUseruuid(), OUTSIDE_REACH);
        }
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

        // Phase 9.2 (Phase 8 rule 8.6): scoped when the caller identifies a human
        // actor; the subject set binds into the WHERE clause, never a post-filter.
        // The employee's own ledger — web and mobile alike — is the OWN case:
        // the actor always reaches themselves, so the flow is unchanged by
        // construction.
        ScopeResolution reach = scope.reachOrNull("expenses:read");
        if (reach == null || reach.unbounded()) {
            return expenseService.findVisibleByUser(useruuid, pageInt, limitInt, null);
        }
        if (!reach.permits(useruuid)) {
            throw new ForbiddenException(OUTSIDE_REACH);
        }
        return expenseService.findVisibleByUser(useruuid, pageInt, limitInt, reach.subjects());
    }

    public List<Expense> findByUser(@PathParam("useruuid") String useruuid) {
        return Expense.find("useruuid = ?1 and status <> ?2", useruuid, STATUS_DELETED).list();
    }

    // Phase 9.2: a project's expense list spans arbitrary owners — no subject
    // filtering exists, so a bounded actor is refused outright (9.4 policy).
    // No BFF route calls this today; machine callers are headerless and pass.
    @GET
    @Path("/project/{projectuuid}/search/period")
    @ScopeEnforced
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
        ScopeResolution reach = scope.reachOrNull("expenses:read");
        if (reach == null || reach.unbounded()) {
            return expenseService.findVisibleByUserAndPeriod(useruuid, localFromDate, localToDate, null);
        }
        if (!reach.permits(useruuid)) {
            throw new ForbiddenException(OUTSIDE_REACH);
        }
        return expenseService.findVisibleByUserAndPeriod(useruuid, localFromDate, localToDate, reach.subjects());
    }

    // Phase 9.2: company-wide review list — bounded actors refused (9.4 policy);
    // HR/ADMIN hold expenses:read at ALL and pass unchanged.
    @GET
    @Path("/search/period")
    @ScopeEnforced
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
    @ScopeEnforced
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
        // Phase 9.2: an acting human files expenses for themselves unless their
        // expenses:review reach is unbounded (HR/ADMIN, e.g. View-as). PUT and
        // DELETE always had an owner check; create was the gap. The BFF pins
        // dto.useruuid to the session/mobile user, so legitimate flows are
        // byte-identical; headerless machine flows are Phase 12 territory.
        String creatingActor = scope.actorOrNull();
        if (creatingActor != null && !creatingActor.equals(dto.getUseruuid())
                && !scope.actorHasUnbounded("expenses:review")) {
            throw new ForbiddenException("Expenses can only be filed for yourself");
        }
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
        // Hand to accounting for a decision. This stays the synchronous default so the
        // W2 AI pass fails closed: if the review never runs, a human already owns it.
        e.setState(ExpenseStateDeriver.NEEDS_ATTENTION);
        e.setAttentionOwner(ExpenseStateDeriver.OWNER_ACCOUNTING);
        e.setAttentionKind(ExpenseStateDeriver.KIND_POLICY);
        e.setDatemodified(java.time.LocalDate.now());
        // W2: after commit, one cheap AI pass judges the justification; accept
        // auto-approves, refer keeps it here with the AI's reservation attached.
        expenseService.queueJustificationAiReview(uuid);
        return Response.noContent().build();
    }

    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"expenses:write"})
    @Transactional
    public void updateOne(@PathParam("uuid") String uuid, Expense expense) {
        Expense existing = expenseService.findByUuid(uuid);
        if (existing == null) {
            throw new WebApplicationException("Expense not found", 404);
        }

        // Phase 9.2: with an actor, "reviewer" means the human's expenses:review
        // reach is unbounded — the client-credential probe always passed for BFF
        // traffic (the system token carries expenses:review for every request,
        // whoever the human was). Headerless callers keep the credential probe.
        String actorUuid = scope.actorOrNull();
        boolean isAccountingReviewer = actorUuid != null
                ? scope.actorHasUnbounded("expenses:review")
                : identity.hasRole("expenses:review");
        boolean isOwner = actorUuid != null && actorUuid.equals(existing.getUseruuid());
        if (!isAccountingReviewer && !isOwner) {
            throw new ForbiddenException("not the expense owner");
        }

        if (expense.getClassification() != null) {
            expense.setUseruuid(existing.getUseruuid());
            classificationService.applyResolvedAccount(expense);
        }

        // Apply the client's edits to the MANAGED entity (null = field not being updated).
        // A bulk JPQL update here would bypass the persistence context: any later dirtying
        // of `existing` (classification below, or maybeReopenForRevalidation) makes the
        // commit flush write the stale pre-edit snapshot back, silently reverting the edit
        // without bumping @Version. Same clobber class as the sync note in
        // ExpenseService.updateStatus.
        if (expense.getAmount() != null) existing.setAmount(expense.getAmount());
        if (expense.getAccount() != null) existing.setAccount(expense.getAccount());
        if (expense.getAccountname() != null) existing.setAccountname(expense.getAccountname());
        if (expense.getDescription() != null) existing.setDescription(expense.getDescription());
        if (expense.getProjectuuid() != null) existing.setProjectuuid(expense.getProjectuuid());
        if (expense.getExpensedate() != null) existing.setExpensedate(expense.getExpensedate());

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

        // If the row is sitting in a review state waiting on the employee, an edit by the
        // owner counts as a fix attempt: clear the review flags, log the edit, and re-fire
        // AI validation. No-op for any other review_state. Reviewer edits are excluded —
        // they must not be logged and attributed as employee fixes.
        if (isOwner) {
            expenseService.maybeReopenForRevalidation(uuid, actorUuid);
        }
    }

    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"expenses:write"})
    @Transactional
    public void delete(@PathParam("uuid") String uuid) {
        log.info("Deleting expense with uuid: "+ uuid);
        Expense expense = expenseService.findByUuid(uuid);
        if (expense == null) {
            throw new WebApplicationException("Expense not found", 404);
        }

        // Phase 9.2: same actor-aware reviewer branch as updateOne.
        String actorUuid = scope.actorOrNull();
        boolean isAccountingReviewer = actorUuid != null
                ? scope.actorHasUnbounded("expenses:review")
                : identity.hasRole("expenses:review");
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
