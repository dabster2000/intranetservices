package dk.trustworks.intranet.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The single catalogue of permission keys in the system.
 *
 * <p>This class is <strong>the sole place a permission key may be defined</strong>
 * (authorization-model-unification plan, Phase 4). The keys are the scope strings
 * historically held in {@code AdminScopeAugmentor.ALL_SCOPES}; the augmentor now
 * reads from here so the two can never diverge.
 *
 * <p>The {@code permission} database table is seeded from this class by
 * {@code PermissionSeedGenerator}, and the committed seed migration is asserted
 * byte-identical to this catalogue by {@code PermissionSeedMigrationTest} — which
 * runs in the fast test tier and therefore blocks every deploy on drift.
 * A {@code @Scheduled} verifier additionally WARNs (never writes) if the runtime
 * table diverges from this class.
 *
 * <p>Do not invent parallel keys. Where finer grain is genuinely needed
 * (e.g. invoice {@code book} versus {@code create}), Phase 12 adds an explicit
 * override annotation on the handful of methods that need it.
 */
public final class Permissions {

    /** The {@code admin:*} wildcard; holders are expanded to every key in the catalogue. */
    public static final String ADMIN_WILDCARD = "admin:*";

    /**
     * One catalogue entry. {@code displayName} and {@code category} feed the
     * {@code permission} table's metadata columns; the {@code key} is the
     * authorization primitive used by {@code @RolesAllowed}.
     */
    public record Permission(String key, String displayName, String category) {}

    /**
     * The full catalogue, grouped as in the original augmentor listing.
     * Order here is the human-curated grouping; consumers needing determinism
     * should use {@link #allKeys()} (sorted).
     */
    public static final List<Permission> CATALOGUE = List.of(
            // Users & HR
            new Permission("users:read", "Users — read", "Users & HR"),
            new Permission("users:write", "Users — write", "Users & HR"),
            new Permission("userstatus:read", "User status — read", "Users & HR"),
            new Permission("userstatus:write", "User status — write", "Users & HR"),
            new Permission("salaries:read", "Salaries — read", "Users & HR"),
            new Permission("salaries:write", "Salaries — write", "Users & HR"),

            // CRM
            new Permission("crm:read", "CRM — read", "CRM"),
            new Permission("crm:write", "CRM — write", "CRM"),

            // Contracts
            new Permission("contracts:read", "Contracts — read", "Contracts"),
            new Permission("contracts:write", "Contracts — write", "Contracts"),

            // Time registration
            new Permission("timeregistration:read", "Time registration — read", "Time registration"),
            new Permission("timeregistration:write", "Time registration — write", "Time registration"),
            new Permission("timeregistration:admin", "Time registration — admin", "Time registration"),

            // Invoicing
            new Permission("invoices:read", "Invoices — read", "Invoicing"),
            new Permission("invoices:write", "Invoices — write", "Invoicing"),

            // Expenses
            new Permission("expenses:read", "Expenses — read", "Expenses"),
            new Permission("expenses:write", "Expenses — write", "Expenses"),
            new Permission("expenses:review", "Expenses — review", "Expenses"),

            // Revenue & utilization
            new Permission("revenue:read", "Revenue — read", "Revenue & utilization"),
            new Permission("utilization:read", "Utilization — read", "Revenue & utilization"),
            new Permission("availability:read", "Availability — read", "Revenue & utilization"),

            // Budgets
            new Permission("budgets:read", "Budgets — read", "Budgets"),
            new Permission("budgets:write", "Budgets — write", "Budgets"),

            // Accounting
            new Permission("accounting:read", "Accounting — read", "Accounting"),
            new Permission("accounting:write", "Accounting — write", "Accounting"),

            // Bonuses
            new Permission("bonus:read", "Bonus — read", "Bonuses"),
            new Permission("bonus:write", "Bonus — write", "Bonuses"),
            new Permission("partnerbonus:read", "Partner bonus — read", "Bonuses"),
            new Permission("partnerbonus:write", "Partner bonus — write", "Bonuses"),
            new Permission("teamleadbonus:read", "Team lead bonus — read", "Bonuses"),
            new Permission("teamleadbonus:write", "Team lead bonus — write", "Bonuses"),

            // Signing
            new Permission("signing:read", "Signing — read", "Signing"),
            new Permission("signing:write", "Signing — write", "Signing"),

            // Dashboard
            new Permission("dashboard:read", "Dashboard — read", "Dashboard"),
            new Permission("dashboard:write", "Dashboard — write", "Dashboard"),

            // Documents
            new Permission("documents:read", "Documents — read", "Documents"),
            new Permission("documents:write", "Documents — write", "Documents"),
            new Permission("documents:gdpr", "Documents — GDPR", "Documents"),

            // Agreements (employee agreement registry — salary-adjacent, HR/ADMIN surfaces only)
            new Permission("agreements:read", "Agreements — read", "Agreements"),
            new Permission("agreements:write", "Agreements — write", "Agreements"),

            // Knowledge
            new Permission("knowledge:read", "Knowledge — read", "Knowledge"),
            new Permission("knowledge:write", "Knowledge — write", "Knowledge"),

            // Competence (SKI 7.b secure-development competence module).
            // Deliberately NOT knowledge:* — /knowledge/courses is elective,
            // enrolment-based learning; this is mandatory, targeted, assessed
            // compliance training whose evidence an auditor reads. Authoring
            // and approving are separate keys so "who writes the test" and
            // "who attests a pass" can be split without a code change — the
            // module's own funktionsadskillelse lesson applied to itself.
            new Permission("competence:read", "Competence — read", "Competence"),
            new Permission("competence:write", "Competence — write", "Competence"),
            new Permission("competence:approve", "Competence — approve", "Competence"),

            // Teams
            new Permission("teams:read", "Teams — read", "Teams"),
            new Permission("teams:write", "Teams — write", "Teams"),

            // Practices
            new Permission("practices:read", "Practices — read", "Practices"),
            new Permission("practices:write", "Practices — write", "Practices"),

            // Career level
            new Permission("careerlevel:read", "Career level — read", "Career level"),
            new Permission("careerlevel:write", "Career level — write", "Career level"),

            // Vacation
            new Permission("vacation:read", "Vacation — read", "Vacation"),
            new Permission("vacation:write", "Vacation — write", "Vacation"),

            // Companies
            new Permission("companies:read", "Companies — read", "Companies"),

            // Capacity
            new Permission("capacity:read", "Capacity — read", "Capacity"),

            // Notifications
            new Permission("notifications:write", "Notifications — write", "Notifications"),

            // Consultant
            new Permission("consultant:read", "Consultant — read", "Consultant"),
            new Permission("consultant:write", "Consultant — write", "Consultant"),

            // Conference
            new Permission("conference:read", "Conference — read", "Conference"),
            new Permission("conference:write", "Conference — write", "Conference"),

            // News
            new Permission("news:read", "News — read", "News"),
            new Permission("news:write", "News — write", "News"),

            // Taskboard
            new Permission("taskboard:read", "Taskboard — read", "Taskboard"),
            new Permission("taskboard:write", "Taskboard — write", "Taskboard"),

            // Devices
            new Permission("devices:read", "Devices — read", "Devices"),
            new Permission("devices:write", "Devices — write", "Devices"),

            // Transportation
            new Permission("transportation:read", "Transportation — read", "Transportation"),
            new Permission("transportation:write", "Transportation — write", "Transportation"),

            // DST statistics
            new Permission("dststatistics:read", "DST statistics — read", "DST statistics"),
            new Permission("dststatistics:write", "DST statistics — write", "DST statistics"),

            // System
            new Permission("system:read", "System — read", "System"),
            new Permission("system:write", "System — write", "System"),

            // Public
            new Permission("public:read", "Public API — read", "Public"),

            // Guest
            new Permission("guest:read", "Guest — read", "Guest"),
            new Permission("guest:write", "Guest — write", "Guest"),

            // Questionnaires
            new Permission("questionnaires:read", "Questionnaires — read", "Questionnaires"),
            new Permission("questionnaires:write", "Questionnaires — write", "Questionnaires"),

            // Bug reports
            new Permission("bugreports:read", "Bug reports — read", "Bug reports"),
            new Permission("bugreports:write", "Bug reports — write", "Bug reports"),
            new Permission("bugreports:admin", "Bug reports — admin", "Bug reports"),

            // Recruitment (interview/refer/comp/gdpr/admin added upfront in
            // ATS expansion P1 — endpoints using them arrive in P3–P19)
            new Permission("recruitment:read", "Recruitment — read", "Recruitment"),
            new Permission("recruitment:write", "Recruitment — write", "Recruitment"),
            new Permission("recruitment:interview", "Recruitment — interview", "Recruitment"),
            new Permission("recruitment:refer", "Recruitment — refer", "Recruitment"),
            new Permission("recruitment:comp", "Recruitment — compensation", "Recruitment"),
            new Permission("recruitment:gdpr", "Recruitment — GDPR", "Recruitment"),
            // The recruiter tier proper (go-live 2026-08-10). recruitment:write
            // is held by every team lead — it is what lets them move a
            // candidate on a position they own — so it could never express
            // "may run the candidate database": create candidates, bulk-tag,
            // pool/decline/withdraw, e-mail candidates, manage templates and
            // the resource library. Those are ADMIN/HR/RECRUITMENT only.
            new Permission("recruitment:manage", "Recruitment — manage candidates", "Recruitment"),
            // Candidate intake for team leads (2026-08-18). Deliberately far
            // narrower than recruitment:manage: it buys creating a candidate
            // and attaching one to a position, and nothing else — no edit, no
            // delete, no pool/unpool, no bulk tag, no candidate e-mail, no
            // templates, no dossier and no record-check outcomes. It exists so
            // a team lead can put a name into the funnel without being handed
            // the whole recruiter tier.
            new Permission("recruitment:intake", "Recruitment — candidate intake", "Recruitment"),
            // Nav-gate split (2026-08-23, docs/access/recruitment-access-model-target.md
            // §5.2). The Inbox (/recruitment/triage) and the Settings surface both hung
            // off recruitment:write, which every team lead holds — so "Inbox yes,
            // Settings no" was inexpressible, and all 20 team leads saw two tabs whose
            // every request 403s. Each surface gets its own key so the two can diverge:
            // decision 12 later opens the Inbox to TEAMLEAD while Settings stays
            // recruiter-tier. These keys gate page/nav visibility; the BFF routes
            // behind the pages keep their own role arrays as the enforcement point.
            new Permission("recruitment:triage", "Recruitment — inbox triage", "Recruitment"),
            new Permission("recruitment:settings", "Recruitment — settings", "Recruitment"),
            new Permission("recruitment:admin", "Recruitment — admin", "Recruitment"),

            // Admin
            new Permission("admin:read", "Admin — read", "Admin"),
            new Permission("admin:write", "Admin — write", "Admin"),
            new Permission(ADMIN_WILDCARD, "Admin — wildcard (all permissions)", "Admin")
    );

    private static final SortedSet<String> ALL_KEYS;
    private static final Map<String, Permission> BY_KEY;

    static {
        Map<String, Permission> byKey = new LinkedHashMap<>();
        for (Permission p : CATALOGUE) {
            if (byKey.put(p.key(), p) != null) {
                throw new IllegalStateException("Duplicate permission key in catalogue: " + p.key());
            }
        }
        BY_KEY = Collections.unmodifiableMap(byKey);
        ALL_KEYS = Collections.unmodifiableSortedSet(
                CATALOGUE.stream().map(Permission::key).collect(Collectors.toCollection(TreeSet::new)));
    }

    /** Every permission key, sorted — the deterministic view used by the augmentor, seed generator and verifier. */
    public static SortedSet<String> allKeys() {
        return ALL_KEYS;
    }

    /** Every permission key as an immutable {@link Set}. */
    public static Set<String> allKeysAsSet() {
        return ALL_KEYS;
    }

    /** Catalogue entry for a key, or {@code null} if the key is not defined. */
    public static Permission byKey(String key) {
        return BY_KEY.get(key);
    }

    private Permissions() {
    }
}
