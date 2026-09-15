package dk.trustworks.intranet.aggregates.crm.merge;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every column in the database that holds a {@code client.uuid}, and what a merge does
 * with each — the reference surface of a client, written down once.
 *
 * <p><b>Why a static list and not a scan.</b> The surface was mapped exhaustively on
 * 2026-09-14 (49 base-table columns; the prod datafix that evening repointed 41 of them)
 * by testing every varchar/char(32..40) column named {@code %client%} or {@code %customer%}
 * against {@code client.uuid}. Name alone is not enough: {@code api_client_scopes.client_uuid}
 * references {@code api_clients}, and {@code conference_participants.clientuuid} is a
 * different id space with zero overlap. A scan at merge time would have to know the same
 * exceptions, and would then silently start rewriting whatever table somebody adds next
 * — a backup table included. A list is reviewable; {@code ClientMergeRegistryTest} pins it
 * against the live schema so a new client-referencing table fails the tier instead of
 * being missed.
 *
 * <p><b>Three classes of column</b> (spec §4):
 * <ul>
 *   <li>{@link #REPOINT} — {@code UPDATE t SET col = winner WHERE col = loser}. Every
 *       column, including the ones below, once their collisions are gone.</li>
 *   <li>{@link #UNIQUE} — the table has a unique key that includes the client column, so
 *       a plain repoint would throw the moment both rows hold an entry. The loser's
 *       colliding rows are removed first, by the rule the {@link Collapse} names.</li>
 *   <li>Views ({@code fact_client_revenue}, {@code fact_pipeline}, ...) — derived from the
 *       tables above and never written to. They are not listed; nothing here is a view.</li>
 * </ul>
 *
 * <p>Table and column names are constants in this class and are the only identifiers ever
 * interpolated into SQL by {@code ClientMergeService}; the uuids are bound parameters.
 */
public final class ClientMergeRegistry {

    /** One column that holds a client uuid. */
    public record Ref(String table, String column) {
        public String key() {
            return table + "." + column;
        }
    }

    /** What a merge does about a unique key that includes the client column. */
    public enum Collapse {
        /**
         * One row per client (a PK on {@code client_uuid}, or a key the survivor already
         * holds): the winner's row stands, the loser's colliding row is removed.
         */
        KEEP_WINNER,
        /**
         * A set per client: the union of both sides, the winner's row standing wherever
         * the same key appears on both — a LinkedIn connection linked to both halves is
         * one connection, not two.
         */
        UNION,
        /**
         * {@code account_person}: a name on both sides is one person. The loser's person
         * row is retired INTO the winner's — its identities, claims and plan-stakeholder
         * links move across before it goes — rather than dropped with everything hanging
         * off it.
         */
        PERSON,
        /** One event projected onto both accounts: preserve the union of its attendees. */
        MEETING,
        /** {@code client_account}: two bands and two owners — a person decides (spec D2). */
        ACCOUNT,
        /** {@code client_month_control}: the row with content wins, the winner on a tie (spec D3). */
        MONTH_CONTROL,
        /**
         * {@code client_economics_sync_failures}: every loser row goes, colliding or not. A
         * queued e-conomic retry for a row that no longer exists on its own is stale, and
         * moved to the winner it would re-sync a customer the merge decided not to touch.
         */
        DROP_ALL,
        /**
         * {@code client_economics_customer}: never touched in e-conomic (spec §5). The
         * winner keeps its mapping per agreement; a loser mapping for an agreement the
         * winner already has is dropped and reported as an orphaned customer number.
         */
        ECONOMICS_CUSTOMER
    }

    /** A unique key that includes the client column, and how it collapses. */
    public record Unique(String table, String column, List<String> keyColumns, Collapse strategy) {}

    /**
     * Every column repointed, alphabetically by table. {@code contracts} and {@code invoices}
     * each carry two of them (spec §4): a merge that repointed only the obvious column would
     * leave the other pointing at a tombstone.
     */
    public static final List<Ref> REPOINT = List.of(
            new Ref("account_calendar_candidate", "client_uuid"),
            new Ref("account_calendar_review", "client_uuid"),
            new Ref("account_meeting", "client_uuid"),
            new Ref("account_person", "client_uuid"),
            new Ref("account_person_identity", "client_uuid"),
            new Ref("account_relation_claim", "client_uuid"),
            new Ref("account_signal", "client_uuid"),
            new Ref("account_slack_digest", "client_uuid"),
            new Ref("account_slack_mention", "client_uuid"),
            new Ref("bid", "client_uuid"),
            new Ref("borrowed_device", "clientuuid"),
            new Ref("calendar_unmatched_domain", "linked_client_uuid"),
            new Ref("client_account", "client_uuid"),
            new Ref("client_account_role", "client_uuid"),
            new Ref("client_activity_log", "client_uuid"),
            new Ref("client_band_history", "client_uuid"),
            new Ref("client_domain", "client_uuid"),
            new Ref("client_economics_contacts", "client_uuid"),
            new Ref("client_economics_customer", "client_uuid"),
            new Ref("client_economics_sync_failures", "client_uuid"),
            new Ref("client_enrichment", "client_uuid"),
            new Ref("client_month_control", "client_uuid"),
            new Ref("client_month_control_history", "client_uuid"),
            new Ref("client_news_state", "client_uuid"),
            new Ref("client_note", "client_uuid"),
            new Ref("client_plan", "client_uuid"),
            new Ref("client_plan_action", "client_uuid"),
            new Ref("client_plan_objective", "client_uuid"),
            new Ref("client_plan_review", "client_uuid"),
            new Ref("client_plan_sentence", "client_uuid"),
            new Ref("client_plan_snapshot", "client_uuid"),
            new Ref("client_plan_stakeholder", "client_uuid"),
            new Ref("contracts", "clientuuid"),
            new Ref("contracts", "billing_client_uuid"),
            new Ref("fact_budget_day", "clientuuid"),
            new Ref("fact_client_revenue_mat", "client_id"),
            new Ref("fact_pipeline_snapshot", "client_uuid"),
            new Ref("fact_project_financials_mat", "client_id"),
            new Ref("files", "relateduuid"),
            new Ref("invoices", "billing_client_uuid"),
            new Ref("invoices", "settlement_billing_client_uuid"),
            new Ref("phantom_client_map", "client_uuid"),
            new Ref("project", "clientuuid"),
            new Ref("projectdescriptions", "clientuuid"),
            new Ref("questionnaire_submission", "client_uuid"),
            new Ref("sales_lead", "clientuuid"),
            new Ref("selfbilled_line", "client_uuid"),
            new Ref("selfbilled_source", "client_uuid"),
            new Ref("slack_unmatched_company", "linked_client_uuid"),
            new Ref("trustlink_company_alias", "client_uuid"),
            new Ref("trustlink_connection", "client_uuid"),
            new Ref("work", "clientuuid")
    );

    /**
     * The unique keys (spec §4b). Key columns are the ones BESIDES the client column; an
     * empty list is a primary key on the client column alone — one row per client.
     */
    public static final List<Unique> UNIQUE = List.of(
            new Unique("account_calendar_candidate", "client_uuid", List.of("event_key"), Collapse.UNION),
            new Unique("account_calendar_review", "client_uuid", List.of("email"), Collapse.UNION),
            new Unique("account_meeting", "client_uuid", List.of("graph_event_id", "user_uuid"), Collapse.MEETING),
            new Unique("account_person", "client_uuid", List.of("name_key"), Collapse.PERSON),
            new Unique("account_person_identity", "client_uuid", List.of("kind", "value"), Collapse.UNION),
            new Unique("account_slack_digest", "client_uuid", List.of("digest_date"), Collapse.KEEP_WINNER),
            new Unique("account_slack_mention", "client_uuid", List.of("channel_id", "mention_date"), Collapse.UNION),
            new Unique("client_account", "client_uuid", List.of(), Collapse.ACCOUNT),
            new Unique("client_account_role", "client_uuid", List.of("user_uuid", "role"), Collapse.UNION),
            new Unique("client_economics_contacts", "client_uuid", List.of("company_uuid", "contact_name"), Collapse.UNION),
            new Unique("client_economics_customer", "client_uuid", List.of("company_uuid"), Collapse.ECONOMICS_CUSTOMER),
            new Unique("client_economics_sync_failures", "client_uuid", List.of("company_uuid"), Collapse.DROP_ALL),
            new Unique("client_enrichment", "client_uuid", List.of(), Collapse.KEEP_WINNER),
            new Unique("client_month_control", "client_uuid", List.of("month"), Collapse.MONTH_CONTROL),
            new Unique("client_news_state", "client_uuid", List.of(), Collapse.KEEP_WINNER),
            new Unique("client_plan", "client_uuid", List.of(), Collapse.KEEP_WINNER),
            new Unique("client_plan_sentence", "client_uuid", List.of("slot"), Collapse.KEEP_WINNER),
            new Unique("client_plan_snapshot", "client_uuid", List.of(), Collapse.KEEP_WINNER),
            // Not a database unique key, but one logo per client is the rule PhotoService keeps:
            // the winner's PHOTO stands, the loser's moves across only when the winner has none.
            new Unique("files", "relateduuid", List.of("type"), Collapse.KEEP_WINNER),
            new Unique("questionnaire_submission", "client_uuid", List.of("questionnaire_uuid", "user_uuid"), Collapse.UNION),
            new Unique("trustlink_company_alias", "client_uuid", List.of("company_name"), Collapse.UNION),
            new Unique("trustlink_connection", "client_uuid", List.of("person_id"), Collapse.UNION)
    );

    /**
     * Tables large enough that one {@code UPDATE} would hold a lock for longer than a
     * request should: repointed in batches of {@link #BATCH_SIZE}. {@code work} is 145k
     * rows; under spec D4 the loser is a prospect and rarely has any, but the loop costs
     * nothing when it runs once.
     */
    public static final Set<String> BATCHED = Set.of("work", "fact_budget_day");

    public static final int BATCH_SIZE = 5000;

    /**
     * Columns that LOOK like client references and are deliberately not touched, with the
     * reason. {@code ClientMergeRegistryTest} accepts a client-shaped column only if it is
     * in {@link #REPOINT} or here, so a new table cannot fall between the two lists
     * unnoticed.
     */
    public static final Map<String, String> IGNORED = Map.ofEntries(
            Map.entry("api_client_scopes.client_uuid", "references api_clients, not client"),
            Map.entry("api_client_audit_log.client_uuid", "references api_clients, not client"),
            Map.entry("conference_participants.clientuuid", "a different id space — zero overlap with client.uuid"),
            Map.entry("contracts_20231128.clientuuid", "a frozen 2023 backup table; a snapshot is not rewritten"),
            Map.entry("contracts_20231128.clientdatauuid", "a frozen 2023 backup table; a snapshot is not rewritten"),
            Map.entry("bak_car_v598.client_uuid", "a migration backup table (V598)"),
            Map.entry("bak_catb_v599.client_uuid", "a migration backup table (V599)"),
            Map.entry("client.uuid", "the key itself"),
            Map.entry("client.merged_into_uuid", "the tombstone itself — chained by ClientMergeService, never a child row"),
            Map.entry("client_activity_log.entity_uuid", "history: 'client X was created' names the row it was, and the row moves with client_uuid"),
            Map.entry("work.contractuuid", "a contract column; the rows whose value is a client uuid are a data defect, and rewriting them would make it look intentional"),
            Map.entry("client_merge_audit.winner_uuid", "the audit trail is history and is never rewritten"),
            Map.entry("client_merge_audit.loser_uuid", "the audit trail is history and is never rewritten")
    );

    private ClientMergeRegistry() {
    }
}
