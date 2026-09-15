package dk.trustworks.intranet.aggregates.crm.merge;

import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRegistry.Collapse;
import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRegistry.Ref;
import dk.trustworks.intranet.aggregates.crm.merge.ClientMergeRegistry.Unique;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the reference surface, without a database. The registry's agreement with
 * the live schema is asserted by {@code ClientMergeServiceIntegrationTest}; what is
 * locked here is that the list cannot contradict itself.
 */
class ClientMergeRegistryTest {

    /** Everything interpolated into SQL is a plain identifier — no quoting, no spaces, no dots. */
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    @Test
    void everyIdentifierIsAPlainSqlName() {
        for (Ref ref : ClientMergeRegistry.REPOINT) {
            assertTrue(IDENTIFIER.matcher(ref.table()).matches(), ref.key());
            assertTrue(IDENTIFIER.matcher(ref.column()).matches(), ref.key());
        }
        for (Unique u : ClientMergeRegistry.UNIQUE) {
            assertTrue(IDENTIFIER.matcher(u.table()).matches(), u.table());
            assertTrue(IDENTIFIER.matcher(u.column()).matches(), u.table());
            for (String key : u.keyColumns()) {
                assertTrue(IDENTIFIER.matcher(key).matches(), u.table() + "." + key);
            }
        }
    }

    @Test
    void noColumnIsRepointedTwice() {
        Set<String> seen = new HashSet<>();
        for (Ref ref : ClientMergeRegistry.REPOINT) {
            assertTrue(seen.add(ref.key()), "listed twice: " + ref.key());
        }
    }

    /** The two tables that carry two client columns each (spec §4) are both listed twice. */
    @Test
    void contractsAndInvoicesCarryTwoColumnsEach() {
        assertEquals(List.of("clientuuid", "billing_client_uuid"), columnsOf("contracts"));
        assertEquals(List.of("billing_client_uuid", "settlement_billing_client_uuid"), columnsOf("invoices"));
    }

    /** A unique key is only worth collapsing on a column that is then repointed. */
    @Test
    void everyUniqueKeyIsOnARepointedColumn() {
        Set<String> repointed = new HashSet<>();
        ClientMergeRegistry.REPOINT.forEach(r -> repointed.add(r.key()));
        for (Unique u : ClientMergeRegistry.UNIQUE) {
            assertTrue(repointed.contains(u.table() + "." + u.column()),
                    "unique key on a column the merge does not repoint: " + u.table() + "." + u.column());
        }
    }

    @Test
    void eachUniqueTableIsListedOnce() {
        Set<String> seen = new HashSet<>();
        for (Unique u : ClientMergeRegistry.UNIQUE) {
            assertTrue(seen.add(u.table()), "listed twice: " + u.table());
        }
    }

    /**
     * The spec's four special cases are each one table, and nothing else claims their
     * strategy: the person union, the account decision, the month-control rule and the
     * e-conomic orphaning are not generic collapses.
     */
    @Test
    void theSpecialCollapsesAreExactlyTheSpecsFour() {
        assertEquals(List.of("account_person"), tablesWith(Collapse.PERSON));
        assertEquals(List.of("account_meeting"), tablesWith(Collapse.MEETING));
        assertEquals(List.of("client_account"), tablesWith(Collapse.ACCOUNT));
        assertEquals(List.of("client_month_control"), tablesWith(Collapse.MONTH_CONTROL));
        assertEquals(List.of("client_economics_customer"), tablesWith(Collapse.ECONOMICS_CUSTOMER));
        assertEquals(List.of("client_economics_sync_failures"), tablesWith(Collapse.DROP_ALL));
    }

    /** A key of the client column alone is a PK — one row per client — and must be KEEP_WINNER. */
    @Test
    void aBareClientKeyIsOneRowPerClient() {
        for (Unique u : ClientMergeRegistry.UNIQUE) {
            if (u.keyColumns().isEmpty()) {
                assertTrue(u.strategy() == Collapse.KEEP_WINNER || u.strategy() == Collapse.ACCOUNT,
                        u.table() + " is one row per client and must keep the winner's (or be the account decision)");
            }
        }
    }

    @Test
    void ignoredColumnsAreNotAlsoRepointed() {
        Set<String> repointed = new HashSet<>();
        ClientMergeRegistry.REPOINT.forEach(r -> repointed.add(r.key()));
        for (String ignored : ClientMergeRegistry.IGNORED.keySet()) {
            assertFalse(repointed.contains(ignored), "both ignored and repointed: " + ignored);
        }
    }

    @Test
    void batchedTablesAreRepointedTables() {
        Set<String> tables = new HashSet<>();
        ClientMergeRegistry.REPOINT.forEach(r -> tables.add(r.table()));
        for (String batched : ClientMergeRegistry.BATCHED) {
            assertTrue(tables.contains(batched), "batched but not repointed: " + batched);
        }
        assertTrue(ClientMergeRegistry.BATCH_SIZE > 0);
    }

    /**
     * The count the spec and the prod datafix were written against. A change here is a
     * change to the reference surface and should be a deliberate edit of both lists — the
     * integration test then proves it against the schema.
     */
    @Test
    void theSurfaceIsTheOneMappedOn2026_09_14() {
        assertEquals(52, ClientMergeRegistry.REPOINT.size(), "repointed columns");
        assertEquals(22, ClientMergeRegistry.UNIQUE.size(), "unique keys including a client column");
    }

    private static List<String> columnsOf(String table) {
        return ClientMergeRegistry.REPOINT.stream().filter(r -> r.table().equals(table)).map(Ref::column).toList();
    }

    private static List<String> tablesWith(Collapse strategy) {
        return ClientMergeRegistry.UNIQUE.stream().filter(u -> u.strategy() == strategy).map(Unique::table).toList();
    }
}
