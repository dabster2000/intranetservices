package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The "Added by" column's query, and the one predicate that keeps it honest.
 *
 * <p>{@code addedByForAll} answers "who put this company into Intra" for every row of
 * {@code GET /accounts} from {@code min(modified_by)} over {@code client_activity_log} where
 * {@code entity_type = 'CLIENT' and action = 'CREATED'}. There is no "this is a creation"
 * flag on that table beyond {@code action}, and {@code min()} over a {@code CHAR(36)} is
 * lexicographic — so ANY other writer of a {@code CLIENT} + {@code CREATED} row can take the
 * column over for a client, permanently, and can become the sole answer on a client older
 * than the log itself.
 *
 * <p>The relationship claim was exactly such a writer: it logged a first claim as
 * {@code CREATED} under a field name of its own, and every employee may file one. The claim
 * path no longer writes {@code CREATED} (see {@code AccountRelationshipClaimActivityTest}),
 * and this is the backstop under that: the two genuine writers ({@code ClientResource} and
 * {@code CalendarSuggestionService}) both go through {@code ClientActivityLogService
 * .logCreated}, which leaves {@code field_name} null, while every per-field writer names a
 * field. So "a row about the client itself, not about one of its fields" is expressible, and
 * the next feature that logs a {@code CREATED} row cannot quietly rewrite the column.
 *
 * <p>Plain JUnit against a mocked {@code EntityManager}: the predicate lives in a native
 * query string, which no compiler and no type checks, and the failure is a wrong human name
 * in a column rather than an error anybody would see.
 */
class AccountAddedByQueryTest {

    private AccountService service;
    private EntityManager em;
    private Query query;

    /** Empty: the shape of the SQL is what is under test, not the row mapping. */
    private final List<Object[]> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AccountService();
        em = mock(EntityManager.class);
        query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        service.em = em;
    }

    @Test
    void onlyRowsWithNoFieldNameCountAsAddingTheCompany() {
        Map<String, PersonDTO> added = service.addedByForAll();
        assertTrue(added.isEmpty());

        String sql = capturedSql();
        assertTrue(sql.contains("l.field_name is null"),
                "without this predicate any CREATED row with a field name — a relationship claim, "
                        + "or whatever the next feature logs — rewrites who added the company: " + sql);
    }

    /** The backstop must NARROW the query, not replace it. The rest of the shape stays. */
    @Test
    void theRestOfTheQueryIsUnchanged() {
        service.addedByForAll();

        String sql = capturedSql();
        assertTrue(sql.contains("min(l.modified_by)"), sql);
        assertTrue(sql.contains("from client_activity_log l"), sql);
        assertTrue(sql.contains("l.entity_type = 'CLIENT'"), sql);
        assertTrue(sql.contains("l.action = 'CREATED'"), sql);
        assertTrue(sql.contains("group by l.client_uuid"), sql);
    }

    /** The query is a text block; compare it with its indentation collapsed. */
    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        return sql.getValue().replaceAll("\\s+", " ").trim();
    }
}
