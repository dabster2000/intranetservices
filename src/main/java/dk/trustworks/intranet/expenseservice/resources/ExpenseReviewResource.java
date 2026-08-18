package dk.trustworks.intranet.expenseservice.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.expenseservice.dto.ExpenseReviewListItemDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import dk.trustworks.intranet.expenseservice.services.AIConfigSnapshot;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;

import dk.trustworks.intranet.expenseservice.services.ExpenseAccountSuggestionService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JBossLog
@Path("/expenses/review")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExpenseReviewResource {

    private static final ObjectReader AI_PRESET_REASONS_READER =
            new ObjectMapper().readerForListOf(String.class);
    private static final String STATUS_DELETED = "DELETED";

    @Inject
    AIConfigSnapshot aiConfigSnapshot;

    @Inject
    ExpenseAccountSuggestionService suggestions;

    @GET
    @Path("/preset-reasons")
    @RolesAllowed({"expenses:review"})
    public List<String> presetReasons() {
        String raw = aiConfigSnapshot.getParameter("hr_approve_reason_presets", "[]");
        try {
            return AI_PRESET_REASONS_READER.readValue(raw);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    @GET
    @RolesAllowed({"expenses:review"})
    public List<ExpenseReviewListItemDTO> queue(
            @QueryParam("segment") String segment,
            @QueryParam("state") String legacyState, // back-compat until Phase 2 re-points the BFF
            @QueryParam("fromDate") String fromDate,
            @QueryParam("toDate") String toDate) {

        LocalDate from = fromDate != null ? LocalDate.parse(fromDate) : null;
        LocalDate to = toDate != null ? LocalDate.parse(toDate) : null;

        // Forward param is `segment`; fall back to the legacy param so the current FE still works.
        String seg = segment != null ? segment : legacyState;
        if (seg == null) seg = "ACCOUNTING";
        // Backward-compat: map the pre-Phase-1 review_state labels to the new segments.
        switch (seg) {
            case "PENDING_HR"        -> seg = "ACCOUNTING";
            case "AWAITING_EMPLOYEE",
                 "HR_SENT_BACK"      -> seg = "EMPLOYEE";
            case "STUCK"             -> seg = "OVERDUE";
            default -> { /* already a new segment or invalid */ }
        }

        List<Expense> rows = switch (seg) {
            // Your decision: accounting-owned POLICY/JUSTIFICATION exceptions. TECHNICAL
            // rows are excluded (P0): approve/send-back are structurally no-ops on them —
            // the entity hook re-derives NEEDS_ATTENTION from UP_FAILED/NO_FILE/NO_USER —
            // so they get their own segment with pipeline actions instead. Classifier-
            // fallback rows (W3) are excluded too — they live in ACCOUNT_ASSIGN.
            case "ACCOUNTING" -> listInbox(ExpenseStateDeriver.OWNER_ACCOUNTING, null, true, Boolean.FALSE, null, from, to);
            // W3: AI cleared the receipt; the only problem is the 9998 classifier
            // fallback. One-click assign-account queue.
            case "ACCOUNT_ASSIGN" -> listInbox(ExpenseStateDeriver.OWNER_ACCOUNTING,
                    ExpenseStateDeriver.KIND_POLICY, true, Boolean.TRUE, null, from, to);
            // Pipeline failures: requeue / close, never approve.
            case "TECHNICAL"  -> listInbox(ExpenseStateDeriver.OWNER_ACCOUNTING,
                    ExpenseStateDeriver.KIND_TECHNICAL, false, null, null, from, to);
            // Waiting on employee: read-only context for accounting.
            case "EMPLOYEE"   -> listInbox(ExpenseStateDeriver.OWNER_EMPLOYEE, null, false, null, null, from, to);
            // Overdue: decisions waiting more than 7 days, anchored on attention_since
            // (queue entry) so decision churn can't reset the clock. Technical rows are
            // excluded — their age shows in the TECHNICAL segment itself.
            case "OVERDUE"    -> listInbox(null, null, true, null, LocalDateTime.now().minusDays(7), from, to);
            // All open exceptions.
            case "ALL"        -> listInbox(null, null, false, null, null, from, to);
            default -> throw new BadRequestException(
                    "segment must be ACCOUNTING, ACCOUNT_ASSIGN, TECHNICAL, EMPLOYEE, OVERDUE, or ALL");
        };

        if ("ACCOUNT_ASSIGN".equals(seg)) {
            // Per-employee suggestion lists, computed once per distinct employee.
            Map<String, List<ExpenseAccountSuggestionService.Suggestion>> byUser = new HashMap<>();
            return rows.stream().map(e -> {
                List<ExpenseAccountSuggestionService.Suggestion> s = e.getUseruuid() == null
                        ? List.of()
                        : byUser.computeIfAbsent(e.getUseruuid(), u -> suggestions.suggestFor(u, 3));
                return toDTO(e, s.stream()
                        .map(x -> new ExpenseReviewListItemDTO.AccountSuggestionDTO(
                                x.account(), x.accountName(), x.timesUsed()))
                        .toList());
            }).toList();
        }
        return rows.stream().map(this::toDTO).toList();
    }

    /**
     * One inbox query over {@code state=NEEDS_ATTENTION}. {@code owner}/{@code kind} filter
     * by attention_owner/attention_kind when non-null; {@code excludeTechnical} drops
     * TECHNICAL rows (decision segments); {@code financeReviewOnly} filters the W3
     * classifier-fallback flag (TRUE = only fallback rows, FALSE = exclude them, null =
     * no filter — NULL flags count as not-fallback); {@code olderThan} adds an
     * attention_since ceiling (overdue). Always excludes DELETED defensively.
     */
    private List<Expense> listInbox(String owner, String kind, boolean excludeTechnical,
                                    Boolean financeReviewOnly,
                                    LocalDateTime olderThan, LocalDate from, LocalDate to) {
        StringBuilder query = new StringBuilder("state = ?1 and status <> ?2");
        List<Object> params = new ArrayList<>();
        params.add(ExpenseStateDeriver.NEEDS_ATTENTION);
        params.add(STATUS_DELETED);
        if (owner != null) {
            query.append(" and attentionOwner = ?").append(params.size() + 1);
            params.add(owner);
        }
        if (kind != null) {
            query.append(" and attentionKind = ?").append(params.size() + 1);
            params.add(kind);
        }
        if (excludeTechnical) {
            query.append(" and (attentionKind is null or attentionKind <> ?").append(params.size() + 1).append(")");
            params.add(ExpenseStateDeriver.KIND_TECHNICAL);
        }
        if (Boolean.TRUE.equals(financeReviewOnly)) {
            query.append(" and financeReviewOnly = true");
        } else if (Boolean.FALSE.equals(financeReviewOnly)) {
            query.append(" and (financeReviewOnly is null or financeReviewOnly = false)");
        }
        if (olderThan != null) {
            query.append(" and attentionSince < ?").append(params.size() + 1);
            params.add(olderThan);
        }
        appendExpenseDateFilters(query, params, from, to);
        return Expense.list(query.toString(), Sort.by("attentionSince", Sort.Direction.Ascending), params.toArray());
    }

    private void appendExpenseDateFilters(StringBuilder query, List<Object> params, LocalDate from, LocalDate to) {
        if (from != null) {
            query.append(" and expensedate >= ?").append(params.size() + 1);
            params.add(from);
        }
        if (to != null) {
            query.append(" and expensedate <= ?").append(params.size() + 1);
            params.add(to);
        }
    }

    // Package-private for unit testing. A null useruuid must not abort the whole
    // queue: findById(null) throws IllegalArgumentException, and one bad row would
    // 500 every segment that includes it.
    ExpenseReviewListItemDTO toDTO(Expense e) {
        return toDTO(e, null);
    }

    ExpenseReviewListItemDTO toDTO(Expense e, List<ExpenseReviewListItemDTO.AccountSuggestionDTO> suggestedAccounts) {
        User u = null;
        if (e.getUseruuid() != null) {
            u = User.findById(e.getUseruuid());
        } else {
            log.warnf("Expense %s is in the review inbox with a null useruuid; listing it without an employee name", e.getUuid());
        }
        String name = u != null ? (u.getFirstname() + " " + u.getLastname()) : null;
        // photoUrl is fetched separately by the frontend via /files/photo/{useruuid}; leave null here
        String photo = null;
        // Age anchor (P0): time since the row entered NEEDS_ATTENTION. Decision churn used
        // to reset datemodified and make a 2024 receipt read "0d"; attention_since is
        // immune. Fallbacks cover rows written before the V508 backfill ran.
        LocalDate base = e.getAttentionSince() != null ? e.getAttentionSince().toLocalDate()
                : (e.getDatemodified() != null ? e.getDatemodified() : e.getDatecreated());
        int days = base != null ? (int) ChronoUnit.DAYS.between(base, LocalDate.now()) : 0;
        return new ExpenseReviewListItemDTO(e, name, photo,
                e.getEmployeeJustification(), e.getAiRuleId(), e.getAiRuleIds(), days,
                e.getVouchernumber() > 0, suggestedAccounts);
    }
}
