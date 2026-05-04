package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link RecruitmentRevisionResponseFilter}.
 * <p>
 * Verifies that placeholder values matching the CPR/salary/pension regex
 * are redacted to {@code [REDACTED]} for callers without {@code users:read},
 * and that no rewrite occurs for callers that hold the scope. The filter is
 * exercised directly with mocked {@link ScopeContext} and JAX-RS contexts —
 * no Quarkus boot.
 */
@ExtendWith(MockitoExtension.class)
class RecruitmentRevisionResponseFilterTest {

    private static final String SCOPE_USERS_READ = "users:read";
    private static final String REDACTED = "[REDACTED]";

    @Mock
    private ScopeContext scopeContext;

    @Mock
    private ContainerRequestContext requestContext;

    @Mock
    private ContainerResponseContext responseContext;

    @InjectMocks
    private RecruitmentRevisionResponseFilter filter;

    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now();
    }

    // -- Caller WITHOUT users:read: sensitive keys redacted --

    @Test
    void filter_withoutUsersReadScope_redactsCprAndSalaryButPreservesName() throws Exception {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("cpr", "1234567890");
        snapshot.put("salary", "50000");
        snapshot.put("name", "Hans");

        RevisionResponse rev = revisionWith(snapshot);
        when(responseContext.getEntity()).thenReturn(rev);
        when(scopeContext.hasScope(SCOPE_USERS_READ)).thenReturn(false);

        filter.filter(requestContext, responseContext);

        RevisionResponse redacted = captureRewrittenEntity();
        assertEquals(REDACTED, redacted.placeholderValuesSnapshot().get("cpr"));
        assertEquals(REDACTED, redacted.placeholderValuesSnapshot().get("salary"));
        assertEquals("Hans", redacted.placeholderValuesSnapshot().get("name"));
    }

    // -- Caller WITH users:read: no rewrite --

    @Test
    void filter_withUsersReadScope_doesNotRewriteEntity() throws Exception {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("cpr", "1234567890");
        snapshot.put("salary", "50000");

        RevisionResponse rev = revisionWith(snapshot);
        when(responseContext.getEntity()).thenReturn(rev);
        when(scopeContext.hasScope(SCOPE_USERS_READ)).thenReturn(true);

        filter.filter(requestContext, responseContext);

        // Filter must short-circuit: no setEntity call.
        verify(responseContext, never()).setEntity(org.mockito.ArgumentMatchers.any());
        // Original snapshot untouched.
        assertEquals("1234567890", rev.placeholderValuesSnapshot().get("cpr"));
        assertEquals("50000", rev.placeholderValuesSnapshot().get("salary"));
    }

    // -- All sensitive key variants are redacted --

    @Test
    void filter_redactsAllSensitiveKeyVariants() throws Exception {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("Cpr", "v1");
        snapshot.put("SALARY", "v2");
        snapshot.put("løn", "v3");
        snapshot.put("lon", "v4");
        snapshot.put("pension", "v5");
        snapshot.put("wage", "v6");
        snapshot.put("gehalt", "v7");
        snapshot.put("LON_2024", "v8");
        snapshot.put("LØN_2024", "v9");      // Unicode uppercase (?iu) folds Ø ↔ ø
        snapshot.put("addressLine1", "Some street 1");

        RevisionResponse rev = revisionWith(snapshot);
        when(responseContext.getEntity()).thenReturn(rev);
        when(scopeContext.hasScope(SCOPE_USERS_READ)).thenReturn(false);

        filter.filter(requestContext, responseContext);

        RevisionResponse redacted = captureRewrittenEntity();
        Map<String, String> result = redacted.placeholderValuesSnapshot();
        assertEquals(REDACTED, result.get("Cpr"));
        assertEquals(REDACTED, result.get("SALARY"));
        assertEquals(REDACTED, result.get("løn"));
        assertEquals(REDACTED, result.get("lon"));
        assertEquals(REDACTED, result.get("pension"));
        assertEquals(REDACTED, result.get("wage"));
        assertEquals(REDACTED, result.get("gehalt"));
        assertEquals(REDACTED, result.get("LON_2024"));
        assertEquals(REDACTED, result.get("LØN_2024"));
        assertEquals("Some street 1", result.get("addressLine1"));
    }

    // -- Empty snapshot → no-op (no exception) --

    @Test
    void filter_emptySnapshot_isNoOp() throws Exception {
        RevisionResponse rev = revisionWith(Map.of());
        when(responseContext.getEntity()).thenReturn(rev);
        when(scopeContext.hasScope(SCOPE_USERS_READ)).thenReturn(false);

        filter.filter(requestContext, responseContext);

        // No rewrite — early return on empty/null snapshot.
        verify(responseContext, never()).setEntity(org.mockito.ArgumentMatchers.any());
    }

    // -- Non-RevisionResponse entity is left unchanged --

    @Test
    void filter_unrelatedEntity_isNoOp() throws Exception {
        String unrelated = "just a string";
        when(responseContext.getEntity()).thenReturn(unrelated);
        when(scopeContext.hasScope(SCOPE_USERS_READ)).thenReturn(false);

        filter.filter(requestContext, responseContext);

        verify(responseContext, never()).setEntity(org.mockito.ArgumentMatchers.any());
    }

    // -- List<RevisionResponse>: each element redacted --

    @Test
    void filter_listOfRevisions_redactsEachElement() throws Exception {
        Map<String, String> s1 = new LinkedHashMap<>();
        s1.put("cpr", "111");
        s1.put("name", "Alpha");
        Map<String, String> s2 = new LinkedHashMap<>();
        s2.put("salary", "999");
        s2.put("name", "Beta");

        RevisionResponse r1 = revisionWith(s1);
        RevisionResponse r2 = revisionWith(s2);
        List<RevisionResponse> list = List.of(r1, r2);

        when(responseContext.getEntity()).thenReturn(list);
        when(scopeContext.hasScope(SCOPE_USERS_READ)).thenReturn(false);

        filter.filter(requestContext, responseContext);

        @SuppressWarnings("unchecked")
        List<RevisionResponse> rewritten = captureRewrittenList();
        assertEquals(2, rewritten.size());
        assertEquals(REDACTED, rewritten.get(0).placeholderValuesSnapshot().get("cpr"));
        assertEquals("Alpha", rewritten.get(0).placeholderValuesSnapshot().get("name"));
        assertEquals(REDACTED, rewritten.get(1).placeholderValuesSnapshot().get("salary"));
        assertEquals("Beta", rewritten.get(1).placeholderValuesSnapshot().get("name"));
        // Confirm we got new instances rather than mutated originals.
        assertNotEquals(r1, rewritten.get(0));
        assertNotEquals(r2, rewritten.get(1));
        // Originals untouched (records are immutable, but verify the map wasn't shared).
        assertEquals("111", r1.placeholderValuesSnapshot().get("cpr"));
        assertEquals("999", r2.placeholderValuesSnapshot().get("salary"));
    }

    // -- Helpers --

    private RevisionResponse revisionWith(Map<String, String> snapshot) {
        return new RevisionResponse(
                "rev-" + java.util.UUID.randomUUID(),
                "dossier-uuid",
                1,
                RevisionKind.REVIEW_EMAIL,
                snapshot,
                List.of(),
                List.of(),
                null,
                null,
                "candidate@example.com",
                "Candidate Name",
                null,
                "actor-uuid",
                now);
    }

    private RevisionResponse captureRewrittenEntity() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(responseContext).setEntity(captor.capture());
        Object value = captor.getValue();
        assertTrue(value instanceof RevisionResponse,
                "Expected RevisionResponse, got " + (value == null ? "null" : value.getClass()));
        return (RevisionResponse) value;
    }

    @SuppressWarnings("unchecked")
    private List<RevisionResponse> captureRewrittenList() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(responseContext).setEntity(captor.capture());
        Object value = captor.getValue();
        assertTrue(value instanceof List<?>,
                "Expected List, got " + (value == null ? "null" : value.getClass()));
        return (List<RevisionResponse>) value;
    }
}
