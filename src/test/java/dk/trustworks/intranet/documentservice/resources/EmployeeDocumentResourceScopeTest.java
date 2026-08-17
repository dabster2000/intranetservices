package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.DataScope;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeEnforced;
import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.security.TestScopeGuards;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of Phase 10.6 enforcement on the employee-document
 * endpoints (access-intent Decision 13: self + HR/ADMIN, team leads
 * deliberately excluded). The file-by-UUID serving routes are the phase
 * file's named highest-risk item: the subject check must key on the row
 * actually served, before any bytes move.
 */
class EmployeeDocumentResourceScopeTest {

    private static final String ACTOR = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OTHER = "bbbbbbbb-0000-0000-0000-000000000002";

    private EmployeeDocumentResource resource;
    private UserEmployeeDocumentResource userResource;
    private EmployeeDocumentService documentService;
    private AuthorizationService authorizationService;
    private RequestHeaderHolder headers;

    @BeforeEach
    void setUp() {
        documentService = mock(EmployeeDocumentService.class);
        authorizationService = mock(AuthorizationService.class);
        headers = mock(RequestHeaderHolder.class);
        ScopeGuard guard = TestScopeGuards.wired(authorizationService, headers);

        resource = new EmployeeDocumentResource();
        resource.employeeDocumentService = documentService;
        resource.requestHeaderHolder = headers;
        resource.scope = guard;

        userResource = new UserEmployeeDocumentResource();
        userResource.employeeDocumentService = documentService;
        userResource.requestHeaderHolder = headers;
        userResource.scope = guard;
    }

    private void actorIs(String actor) {
        when(headers.getUserUuid()).thenReturn(actor);
    }

    private void subjectDecision(String permission, String subject, boolean allowed) {
        when(authorizationService.decideSubjectAccess(eq(ACTOR), eq(permission), eq(subject), any(), anySet()))
                .thenReturn(new AuthorizationService.AccessDecision(
                        allowed, allowed ? DataScope.ALL : DataScope.OWN, allowed, 0,
                        allowed ? "reach" : "subject outside reach"));
    }

    private EmployeeDocument docOwnedBy(String owner) {
        EmployeeDocument doc = new EmployeeDocument();
        doc.setUuid("doc-uuid-1");
        doc.setUserUuid(owner);
        return doc;
    }

    // ---- The named highest-risk path: file serving by UUID ------------------

    @Test
    void contentOfAnotherUsersDocumentIs403ForABoundedActor() {
        actorIs(ACTOR);
        when(documentService.get("doc-uuid-1")).thenReturn(docOwnedBy(OTHER));
        subjectDecision(EmployeeDocumentResource.READ_SCOPE, OTHER, false);
        assertThrows(ForbiddenException.class, () -> resource.content("doc-uuid-1"));
        verify(documentService, never()).download(any(), any());
    }

    @Test
    void contentOfOwnDocumentPasses() {
        actorIs(ACTOR);
        when(documentService.get("doc-uuid-1")).thenReturn(docOwnedBy(ACTOR));
        subjectDecision(EmployeeDocumentResource.READ_SCOPE, ACTOR, true);
        when(documentService.download(eq("doc-uuid-1"), eq(ACTOR)))
                .thenReturn(new EmployeeDocumentService.DocumentContent(
                        new byte[]{1}, "application/pdf", "contract.pdf"));
        resource.content("doc-uuid-1");
        verify(documentService).download(eq("doc-uuid-1"), eq(ACTOR));
    }

    @Test
    void deleteOfAnotherUsersDocumentIs403ForABoundedActor() {
        actorIs(ACTOR);
        when(documentService.get("doc-uuid-1")).thenReturn(docOwnedBy(OTHER));
        subjectDecision(EmployeeDocumentResource.WRITE_SCOPE, OTHER, false);
        assertThrows(ForbiddenException.class, () -> resource.delete("doc-uuid-1"));
        verify(documentService, never()).delete(any(), any());
    }

    // ---- List + upload: the path's useruuid is the subject ------------------

    @Test
    void listAnotherUsersDocumentsIs403ForABoundedActor() {
        actorIs(ACTOR);
        subjectDecision(EmployeeDocumentResource.READ_SCOPE, OTHER, false);
        assertThrows(ForbiddenException.class, () -> userResource.list(OTHER, false, false));
        verify(documentService, never()).list(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void listOwnDocumentsPassesForABoundedActor() {
        actorIs(ACTOR);
        subjectDecision(EmployeeDocumentResource.READ_SCOPE, ACTOR, true);
        when(documentService.list(eq(ACTOR), anyBoolean(), anyBoolean())).thenReturn(List.of());
        userResource.list(ACTOR, false, false);
        verify(documentService).list(eq(ACTOR), eq(false), eq(false));
    }

    @Test
    void headerlessCallerIsUntouched() {
        actorIs(null);
        when(documentService.list(eq(OTHER), anyBoolean(), anyBoolean())).thenReturn(List.of());
        userResource.list(OTHER, true, true);
        verify(documentService).list(eq(OTHER), eq(true), eq(true));
        verify(authorizationService, never()).decideSubjectAccess(any(), any(), any(), any(), anySet());
    }

    // ---- Placement pins -----------------------------------------------------

    @Test
    void companyWideAndGdprSurfacesAreScopeEnforced() {
        for (String name : List.of("reviewQueue", "erase", "dsarExport", "stats", "retentionPreview")) {
            Method m = Arrays.stream(EmployeeDocumentResource.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertTrue(m.isAnnotationPresent(ScopeEnforced.class),
                    name + " must be @ScopeEnforced — no per-actor subject dimension");
        }
    }

    @Test
    void perDocumentSurfacesAreNotScopeEnforced() {
        // @ScopeEnforced would 403 an OWN-scoped employee opening their own
        // contract; the resolved-owner subject check replaces it here.
        for (String name : List.of("content", "get", "patch", "delete")) {
            Method m = Arrays.stream(EmployeeDocumentResource.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertFalse(m.isAnnotationPresent(ScopeEnforced.class),
                    name + " must NOT be @ScopeEnforced — it has a per-document subject");
        }
    }
}
