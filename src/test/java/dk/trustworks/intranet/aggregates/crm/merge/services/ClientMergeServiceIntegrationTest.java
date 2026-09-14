package dk.trustworks.intranet.aggregates.crm.merge.services;

import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRegistry;
import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRegistry.Ref;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergePreviewDTO;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergeRequest;
import dk.trustworks.intranet.aggregates.crm.merge.dto.ClientMergeResultDTO;
import dk.trustworks.intranet.aggregates.finance.health.IntercompanyClassificationCheck;
import dk.trustworks.intranet.aggregates.finance.jobs.EconomicRevenueImportBatchlet;
import dk.trustworks.intranet.aggregates.finance.jobs.OpexDistributionRefreshBatchlet;
import dk.trustworks.intranet.expenseservice.services.PromptBootstrapper;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merge against a real MariaDB — the spec's "a {@code @QuarkusTest} proves the
 * 42-table repoint against a seeded pair" (§10.6), and the registry's agreement with the
 * live schema.
 *
 * <p>Opt in with {@code -Dclient-merge.integration=true} against a local copy of the
 * schema (see {@code reference_run_quarkustest_against_local_db}). Every row it seeds
 * carries a fresh uuid and is removed afterwards; nothing it does depends on the copy's
 * own clients beyond borrowing one user and two company uuids as foreign keys.
 */
@QuarkusTest
@TestProfile(ClientMergeServiceIntegrationTest.LocalDbProfile.class)
@EnabledIfSystemProperty(named = "client-merge.integration", matches = "true")
class ClientMergeServiceIntegrationTest {

    public static class LocalDbProfile implements QuarkusTestProfile {
        static final List<Class<?>> EXCLUDED_STARTUP_JOBS = List.of(
                OpexDistributionRefreshBatchlet.class, EconomicRevenueImportBatchlet.class,
                IntercompanyClassificationCheck.class, PromptBootstrapper.class);

        @Override
        public boolean disableApplicationLifecycleObservers() {
            return true;
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.flyway.migrate-at-start", "false",
                    "quarkus.flyway.repair-at-start", "false",
                    "quarkus.scheduler.enabled", "false",
                    "quarkus.arc.exclude-types", String.join(",", EXCLUDED_STARTUP_JOBS.stream().map(Class::getName).toList()),
                    "quarkus.mailer.mock", "true",
                    "quarkus.otel.sdk.disabled", "true",
                    "cvtool.username", "test", "cvtool.password", "test");
        }
    }

    @Inject ClientMergeService service;
    @Inject EntityManager em;
    @Inject RequestHeaderHolder headers;

    static final String ACTOR = "00000000-0000-4000-8000-00000000ac70";
    static final String CLAIMANT = "00000000-0000-4000-8000-00000000c1a1";
    static final String CLAIMANT_2 = "00000000-0000-4000-8000-00000000c1a2";
    static final int WORK_ROWS = ClientMergeRegistry.BATCH_SIZE + 17;

    String winner, loser, older, someUser, companyA, companyB;
    String winnerPerson, loserPerson, loserOnlyPerson;

    @BeforeEach
    void seed() {
        winner = uuid();
        loser = uuid();
        older = uuid();
        winnerPerson = uuid();
        loserPerson = uuid();
        loserOnlyPerson = uuid();
        tx(() -> {
            someUser = (String) em.createNativeQuery("SELECT uuid FROM user ORDER BY uuid LIMIT 1").getSingleResult();
            @SuppressWarnings("unchecked")
            List<String> companies = em.createNativeQuery("SELECT uuid FROM companies ORDER BY uuid LIMIT 2").getResultList();
            assertEquals(2, companies.size(), "the copy needs two companies (agreements)");
            companyA = companies.get(0);
            companyB = companies.get(1);

            client(winner, "MergeTest Danmarks Statistik", "CLIENT", "99990001", null);
            client(loser, "MergeTest Danmarks Statistik (prospect)", "PROSPECT", null, someUser);
            client(older, "MergeTest older tombstone", "PROSPECT", null, null);
            exec("UPDATE client SET merged_into_uuid = :l, merged_at = NOW() WHERE uuid = :o", Map.of("l", loser, "o", older));

            // The people registry: one name on both sides, one only on the loser.
            person(winnerPerson, winner, "Sif Broby Madsen", "sif broby madsen");
            person(loserPerson, loser, "Sif Broby Madsen", "sif broby madsen");
            person(loserOnlyPerson, loser, "Ole Olsen", "ole olsen");
            identity(winnerPerson, winner, "EMAIL", "sif@dst.dk");
            identity(loserPerson, loser, "EMAIL", "sif@dst.dk");          // collides — dropped
            identity(loserPerson, loser, "EMAIL", "sif.broby@dst.dk");    // moves to the winner's person
            identity(loserOnlyPerson, loser, "EMAIL", "ole@dst.dk");      // repointed with its person
            claim(winnerPerson, winner, CLAIMANT, 2);
            claim(loserPerson, loser, CLAIMANT, 3);                        // collides on (person, user) — dropped
            claim(loserPerson, loser, CLAIMANT_2, 1);                      // moves to the winner's person

            // TrustLink: the same connection on both halves, and one only on the loser.
            connection(winner, "p1");
            connection(loser, "p1");
            connection(loser, "p2");

            // Two accounts — a person decides.
            account(winner, "ACTIVE");
            account(loser, "STRATEGIC");

            // Month controls: August the loser annotated (loser keeps), July the winner approved (winner keeps).
            monthControl(winner, "2026-08-01", null, null);
            monthControl(loser, "2026-08-01", null, "PO arrives in September");
            monthControl(winner, "2026-07-01", "2026-08-02 09:00:00", null);
            monthControl(loser, "2026-07-01", null, null);

            // e-conomic: company A on both sides with different numbers (orphaned), B only on the loser (moves).
            economics(winner, companyA, 100);
            economics(loser, companyA, 200);
            economics(loser, companyB, 300);

            // Plain child rows, including enough work to need two batches.
            exec("INSERT INTO client_activity_log (client_uuid, entity_type, entity_uuid, action, modified_by, modified_at) "
                    + "VALUES (:c, 'CLIENT', :c, 'CREATED', :a, NOW())", Map.of("c", loser, "a", ACTOR));
            exec("INSERT INTO client_note (uuid, client_uuid, note_text, author_uuid, created_at) VALUES (:u, :c, 'Called them', :a, NOW())",
                    Map.of("u", uuid(), "c", loser, "a", ACTOR));
            exec("INSERT INTO files (uuid, relateduuid, type, name, filename, uploaddate) VALUES (:u, :c, 'PHOTO', 'logo', 'mergetest.png', CURDATE())",
                    Map.of("u", uuid(), "c", loser));
            StringBuilder work = new StringBuilder("INSERT INTO work (uuid, taskuuid, useruuid, clientuuid) VALUES ");
            for (int i = 0; i < WORK_ROWS; i++) {
                if (i > 0) work.append(',');
                work.append("('").append(uuid()).append("','").append(uuid()).append("','").append(ACTOR).append("','").append(loser).append("')");
            }
            em.createNativeQuery(work.toString()).executeUpdate();
        });
    }

    @AfterEach
    void cleanUp() {
        tx(() -> {
            List<String> mine = List.of(winner, loser, older);
            for (Ref ref : ClientMergeRegistry.REPOINT) {
                if (tableExists(ref.table())) {
                    em.createNativeQuery("DELETE FROM `" + ref.table() + "` WHERE `" + ref.column() + "` IN (:mine)")
                            .setParameter("mine", mine).executeUpdate();
                }
            }
            em.createNativeQuery("DELETE FROM client_merge_audit WHERE winner_uuid IN (:mine) OR loser_uuid IN (:mine)")
                    .setParameter("mine", mine).executeUpdate();
            em.createNativeQuery("DELETE FROM client WHERE uuid IN (:mine)").setParameter("mine", mine).executeUpdate();
        });
    }

    // ------------------------------------------------------------------------

    @Test
    @ActivateRequestContext
    void thePreviewDescribesEveryDecisionBeforeAnythingIsWritten() {
        headers.setUserUuid(ACTOR);
        ClientMergePreviewDTO preview = service.preview(winner, loser);

        assertNull(preview.problem());
        assertEquals("CLIENT", preview.winner().type());
        assertEquals("PROSPECT", preview.loser().type());
        assertNotNull(preview.account(), "both rows have an account — the dialog has to ask");
        assertEquals("ACTIVE", preview.account().winner().band());
        assertEquals("STRATEGIC", preview.account().loser().band());

        assertEquals(2, preview.monthControls().size());
        Map<String, String> keep = preview.monthControls().stream()
                .collect(Collectors.toMap(m -> m.month().toString(), ClientMergePreviewDTO.MonthControlDecision::keep));
        assertEquals("LOSER", keep.get("2026-08-01"), "the annotated row wins");
        assertEquals("WINNER", keep.get("2026-07-01"), "the approved row wins");

        assertEquals(1, preview.orphanedEconomics().size());
        assertEquals(200, preview.orphanedEconomics().get(0).customerNumber());
        assertEquals(100, preview.orphanedEconomics().get(0).winnerCustomerNumber());
        assertFalse(preview.orphanedEconomics().get(0).identical());
        assertEquals(1, preview.movedEconomics().size());
        assertEquals(300, preview.movedEconomics().get(0).customerNumber());

        assertEquals(WORK_ROWS, rows(preview.moves(), "work"));
        assertEquals(2, rows(preview.dropped(), "trustlink_connection") + rows(preview.dropped(), "account_person"),
                "one duplicate connection and one duplicate person");
        assertTrue(preview.skippedTables().isEmpty(), "every registry table exists here: " + preview.skippedTables());

        // Nothing moved.
        assertEquals(WORK_ROWS, count("work", "clientuuid", loser));
        assertNull(scalar("SELECT merged_into_uuid FROM client WHERE uuid = :u", loser));
    }

    @Test
    @ActivateRequestContext
    void aCustomerIsRefusedAsTheLoserAndTheRefusalIsTheSameSentenceAsThePreviewsProblem() {
        headers.setUserUuid(ACTOR);
        ClientMergePreviewDTO preview = service.preview(loser, winner);
        assertNotNull(preview.problem());
        assertTrue(preview.problem().contains("is a customer"), preview.problem());

        WebApplicationException refused = assertThrows(WebApplicationException.class,
                () -> service.merge(loser, winner, new ClientMergeRequest("WINNER")));
        assertEquals(409, refused.getResponse().getStatus());
        assertEquals(preview.problem(), refused.getMessage());
        assertEquals(WORK_ROWS, count("work", "clientuuid", loser), "nothing moved");
    }

    @Test
    @ActivateRequestContext
    void withTwoAccountsTheMergeInsistsOnADecision() {
        headers.setUserUuid(ACTOR);
        WebApplicationException refused = assertThrows(WebApplicationException.class,
                () -> service.merge(winner, loser, new ClientMergeRequest(null)));
        assertEquals(400, refused.getResponse().getStatus());
        assertNull(scalar("SELECT merged_into_uuid FROM client WHERE uuid = :u", loser), "nothing written");
    }

    @Test
    @ActivateRequestContext
    void theMergeMovesEverythingCollapsesByTheRulesTombstonesAndVerifies() {
        headers.setUserUuid(ACTOR);
        ClientMergeResultDTO result = service.merge(winner, loser, new ClientMergeRequest("LOSER"));

        // The tombstone, and the chain: the older tombstone now points at the winner in one hop.
        assertEquals(winner, scalar("SELECT merged_into_uuid FROM client WHERE uuid = :u", loser));
        assertNotNull(scalar("SELECT merged_at FROM client WHERE uuid = :u", loser));
        assertEquals(winner, scalar("SELECT merged_into_uuid FROM client WHERE uuid = :u", older));

        // Nothing references the loser any more — the same check the service ran before
        // committing — except the one line the merge writes on the tombstone afterwards.
        for (Ref ref : ClientMergeRegistry.REPOINT) {
            if ("client_activity_log".equals(ref.table())) {
                assertEquals(1, count(ref.table(), ref.column(), loser), "the tombstone keeps exactly its merged_into line");
                continue;
            }
            assertEquals(0, count(ref.table(), ref.column(), loser), "still referencing the loser: " + ref.key());
        }
        // The loser's logo came across because the winner had none.
        assertEquals(1, scalarLong("SELECT COUNT(*) FROM files WHERE relateduuid = :u AND type = 'PHOTO'", winner));
        assertEquals(WORK_ROWS, count("work", "clientuuid", winner), "the batched repoint moved every work row");
        assertEquals(WORK_ROWS, rows(result.moves(), "work"));

        // D2: the loser's account and owner were chosen.
        assertEquals("STRATEGIC", scalar("SELECT band FROM client_account WHERE client_uuid = :u", winner));
        assertEquals(0, count("client_account", "client_uuid", loser));
        assertEquals(someUser, scalar("SELECT accountmanager FROM client WHERE uuid = :u", winner));

        // The people registry: one Sif, with the union of identities and claims; Ole came across whole.
        assertEquals(2, count("account_person", "client_uuid", winner));
        assertEquals(1, scalarLong("SELECT COUNT(*) FROM account_person WHERE client_uuid = :u AND name_key = 'sif broby madsen'", winner));
        assertEquals(winnerPerson, scalar("SELECT uuid FROM account_person WHERE client_uuid = :u AND name_key = 'sif broby madsen'", winner));
        assertEquals(Set.of("sif@dst.dk", "sif.broby@dst.dk"),
                strings("SELECT value FROM account_person_identity WHERE person_uuid = :u", winnerPerson));
        assertEquals(Set.of("ole@dst.dk"),
                strings("SELECT value FROM account_person_identity WHERE person_uuid = :u", loserOnlyPerson));
        assertEquals(3, count("account_person_identity", "client_uuid", winner));
        assertEquals(Set.of(CLAIMANT, CLAIMANT_2),
                strings("SELECT user_uuid FROM account_relation_claim WHERE person_uuid = :u", winnerPerson));
        assertEquals(2, scalarLong("SELECT strength FROM account_relation_claim WHERE person_uuid = :u AND user_uuid = '" + CLAIMANT + "'", winnerPerson),
                "the winner's own claim stood; the loser's colliding one was dropped");

        // TrustLink: the union, once each.
        assertEquals(Set.of("p1", "p2"), strings("SELECT person_id FROM trustlink_connection WHERE client_uuid = :u", winner));
        assertEquals(2, count("trustlink_connection", "client_uuid", winner));

        // D3: August kept the loser's annotated row, July the winner's approved row.
        assertEquals("PO arrives in September",
                scalar("SELECT note FROM client_month_control WHERE client_uuid = :u AND month = '2026-08-01'", winner));
        assertNotNull(scalar("SELECT approved_at FROM client_month_control WHERE client_uuid = :u AND month = '2026-07-01'", winner));
        assertEquals(2, count("client_month_control", "client_uuid", winner));

        // §5: the winner kept 100 for company A, gained 300 for company B, and 200 is reported as orphaned.
        assertEquals(100L, scalarLong("SELECT customer_number FROM client_economics_customer WHERE client_uuid = :u AND company_uuid = '" + companyA + "'", winner));
        assertEquals(300L, scalarLong("SELECT customer_number FROM client_economics_customer WHERE client_uuid = :u AND company_uuid = '" + companyB + "'", winner));
        assertEquals(1, result.orphanedEconomics().size());
        assertEquals(200, result.orphanedEconomics().get(0).customerNumber());
        assertEquals(1, result.movedEconomics().size());

        // The audit row and the two activity lines.
        assertEquals(1, scalarLong("SELECT COUNT(*) FROM client_merge_audit WHERE winner_uuid = :u AND loser_uuid = '" + loser + "' AND actor_uuid = '" + ACTOR + "'", winner));
        String orphanedJson = scalar("SELECT orphaned_economics_json FROM client_merge_audit WHERE winner_uuid = :u", winner);
        assertTrue(orphanedJson.contains("\"customerNumber\":200"), orphanedJson);
        assertEquals(1, scalarLong("SELECT COUNT(*) FROM client_activity_log WHERE client_uuid = :u AND action = 'MERGED' AND field_name = 'merged_from'", winner));
        assertEquals(1, scalarLong("SELECT COUNT(*) FROM client_activity_log WHERE client_uuid = :u AND action = 'MERGED' AND field_name = 'merged_into'", loser));
        // The loser's own history followed it to the winner (its CREATED line), so the feed reads whole.
        assertEquals(1, scalarLong("SELECT COUNT(*) FROM client_activity_log WHERE client_uuid = :u AND action = 'CREATED'", winner));

        // Merging the same pair again is refused: the loser is a tombstone now.
        WebApplicationException again = assertThrows(WebApplicationException.class,
                () -> service.merge(winner, loser, new ClientMergeRequest("LOSER")));
        assertEquals(409, again.getResponse().getStatus());
        assertTrue(again.getMessage().contains("already been merged"), again.getMessage());
    }

    /**
     * The registry against the live schema: every uuid-shaped column in a base table whose
     * values are client uuids is either repointed or deliberately ignored. A new table that
     * hangs off {@code client.uuid} fails here instead of being silently left behind by
     * the next merge. Runs against the copy's real rows, which is the point.
     */
    @Test
    void everyClientReferencingColumnInTheSchemaIsInTheRegistry() {
        Set<String> known = new java.util.HashSet<>(ClientMergeRegistry.IGNORED.keySet());
        ClientMergeRegistry.REPOINT.forEach(r -> known.add(r.key()));
        List<String> missing = new ArrayList<>();
        tx(() -> {
            @SuppressWarnings("unchecked")
            List<Object[]> columns = em.createNativeQuery("""
                    SELECT c.TABLE_NAME, c.COLUMN_NAME
                    FROM information_schema.COLUMNS c
                    JOIN information_schema.TABLES t ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
                    WHERE c.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE'
                      AND c.DATA_TYPE IN ('varchar', 'char') AND c.CHARACTER_MAXIMUM_LENGTH BETWEEN 32 AND 40
                    """).getResultList();
            for (Object[] column : columns) {
                String table = String.valueOf(column[0]);
                String col = String.valueOf(column[1]);
                if (known.contains(table + "." + col) || table.startsWith("bak_") || table.endsWith("_20231128")) {
                    continue;
                }
                // A few legacy tables collate utf8mb4_danish_ci; the comparison is forced to one collation.
                Number hits = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM `" + table + "` WHERE CONVERT(`" + col + "` USING utf8mb4) COLLATE utf8mb4_general_ci "
                                + "IN (SELECT CONVERT(uuid USING utf8mb4) COLLATE utf8mb4_general_ci FROM client)").getSingleResult();
                if (hits.longValue() > 0) {
                    missing.add(table + "." + col + " (" + hits + " rows)");
                }
            }
        });
        assertTrue(missing.isEmpty(), "client-referencing columns the registry does not know: " + missing);
    }

    // ---- seeding helpers -----------------------------------------------------------

    private void client(String uuid, String name, String type, String cvr, String accountManager) {
        exec("INSERT INTO client (uuid, name, type, cvr, accountmanager, created, segment, managed, billing_country, currency) "
                + "VALUES (:u, :n, :t, :c, :am, NOW(), 'OTHER', 'INTRA', 'DK', 'DKK')",
                nullable("u", uuid, "n", name, "t", type, "c", cvr, "am", accountManager));
    }

    private void person(String uuid, String client, String name, String nameKey) {
        exec("INSERT INTO account_person (uuid, client_uuid, name, name_key, kind, sources, first_seen_at, last_seen_at) "
                + "VALUES (:u, :c, :n, :k, 'CONTACT', 'CALENDAR', NOW(), NOW())",
                Map.of("u", uuid, "c", client, "n", name, "k", nameKey));
    }

    private void identity(String person, String client, String kind, String value) {
        exec("INSERT INTO account_person_identity (uuid, person_uuid, client_uuid, kind, value, first_seen_at, last_seen_at) "
                + "VALUES (:u, :p, :c, :k, :v, NOW(), NOW())",
                Map.of("u", uuid(), "p", person, "c", client, "k", kind, "v", value));
    }

    private void claim(String person, String client, String user, int strength) {
        exec("INSERT INTO account_relation_claim (uuid, client_uuid, person_uuid, user_uuid, strength, claimed_at, updated_at) "
                + "VALUES (:u, :c, :p, :us, :s, NOW(), NOW())",
                Map.of("u", uuid(), "c", client, "p", person, "us", user, "s", strength));
    }

    private void connection(String client, String personId) {
        exec("INSERT INTO trustlink_connection (uuid, client_uuid, person_id, full_name, tier, company_name, is_customer, "
                + "coffee_count, comment_count, first_seen_at, last_seen_at) "
                + "VALUES (:u, :c, :p, 'Somebody', 1, 'MergeTest', 0, 0, 0, NOW(), NOW())",
                Map.of("u", uuid(), "c", client, "p", personId));
    }

    private void account(String client, String band) {
        exec("INSERT INTO client_account (client_uuid, band, created_at, created_by, modified_at, modified_by) "
                + "VALUES (:c, :b, NOW(), :a, NOW(), :a)", Map.of("c", client, "b", band, "a", ACTOR));
    }

    private void monthControl(String client, String month, String approvedAt, String note) {
        exec("INSERT INTO client_month_control (uuid, client_uuid, month, approved_at, note, created_at) "
                + "VALUES (:u, :c, :m, :ap, :n, NOW())",
                nullable("u", uuid(), "c", client, "m", month, "ap", approvedAt, "n", note));
    }

    private void economics(String client, String company, int number) {
        exec("INSERT INTO client_economics_customer (uuid, client_uuid, company_uuid, customer_number, pairing_source, synced_at) "
                + "VALUES (:u, :c, :co, :n, 'MANUAL', NOW())",
                Map.of("u", uuid(), "c", client, "co", company, "n", number));
    }

    // ---- plumbing -----------------------------------------------------------------

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private void tx(Runnable body) {
        QuarkusTransaction.requiringNew().run(body);
    }

    private void exec(String sql, Map<String, Object> params) {
        var q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        q.executeUpdate();
    }

    /** {@code Map.of} refuses null values; a seed row legitimately has some. */
    private static Map<String, Object> nullable(Object... kv) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private boolean tableExists(String table) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :t")
                .setParameter("t", table).getSingleResult()).longValue() > 0;
    }

    private long count(String table, String column, String uuid) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "` = :u").setParameter("u", uuid).getSingleResult()).longValue());
    }

    private String scalar(String sql, String uuid) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> rows = em.createNativeQuery(sql).setParameter("u", uuid).getResultList();
            return rows.isEmpty() || rows.get(0) == null ? null : String.valueOf(rows.get(0));
        });
    }

    private long scalarLong(String sql, String uuid) {
        return QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery(sql).setParameter("u", uuid).getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    private Set<String> strings(String sql, String uuid) {
        return QuarkusTransaction.requiringNew().call(() ->
                ((List<Object>) em.createNativeQuery(sql).setParameter("u", uuid).getResultList())
                        .stream().map(String::valueOf).collect(Collectors.toSet()));
    }

    private static long rows(List<ClientMergePreviewDTO.TableCount> counts, String table) {
        return counts.stream().filter(c -> c.table().equals(table)).mapToLong(ClientMergePreviewDTO.TableCount::rows).sum();
    }
}
