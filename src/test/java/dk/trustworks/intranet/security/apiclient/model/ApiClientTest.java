package dk.trustworks.intranet.security.apiclient.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Domain entity tests for ApiClient aggregate root.
 * These test business logic in isolation — no framework dependencies.
 */
class ApiClientTest {

    private static final Set<String> DEFAULT_SCOPES = Set.of("users:read", "SYSTEM");

    private ApiClient.CreationResult createTestClient() {
        return ApiClient.create("test-client", "Test Client", "desc", 3600, DEFAULT_SCOPES, "admin");
    }

    // -- Factory --

    @Test
    void create_validInputs_returnsClientWithHashedSecret() {
        var result = createTestClient();
        assertNotNull(result.client());
        assertNotNull(result.plaintextSecret());
        assertNotNull(result.client().getUuid());
        assertEquals("test-client", result.client().getClientId());
        assertEquals("Test Client", result.client().getName());
        assertTrue(result.client().isEnabled());
        assertNull(result.client().getDeletedAt());
        assertTrue(result.client().isActive());
        assertEquals(3600, result.client().getTokenTtlSeconds());
        assertEquals("admin", result.client().getCreatedBy());
    }

    @Test
    void create_setsScopes() {
        var result = createTestClient();
        assertEquals(Set.of("users:read", "SYSTEM"), result.client().getScopeNames());
    }

    @Test
    void create_defaultsTtlTo3600WhenZeroOrNegative() {
        var result = ApiClient.create("c", "n", null, 0, Set.of("a:b"), "admin");
        assertEquals(3600, result.client().getTokenTtlSeconds());

        var result2 = ApiClient.create("c2", "n2", null, -1, Set.of("a:b"), "admin");
        assertEquals(3600, result2.client().getTokenTtlSeconds());
    }

    @Test
    void create_nullClientId_throws() {
        assertThrows(NullPointerException.class,
                () -> ApiClient.create(null, "n", null, 3600, Set.of(), "admin"));
    }

    // -- Credential validation --

    @Test
    void validateCredentials_correctSecret_returnsTrue() {
        var result = createTestClient();
        assertTrue(result.client().validateCredentials(result.plaintextSecret()));
    }

    @Test
    void validateCredentials_wrongSecret_returnsFalse() {
        var result = createTestClient();
        assertFalse(result.client().validateCredentials("wrong-secret"));
    }

    @Test
    void validateCredentials_nullSecret_returnsFalse() {
        var result = createTestClient();
        assertFalse(result.client().validateCredentials(null));
    }

    @Test
    void validateCredentials_blankSecret_returnsFalse() {
        var result = createTestClient();
        assertFalse(result.client().validateCredentials("  "));
    }

    // -- isActive --

    @Test
    void isActive_enabledAndNotDeleted_returnsTrue() {
        var client = createTestClient().client();
        assertTrue(client.isActive());
    }

    @Test
    void isActive_disabled_returnsFalse() {
        var client = createTestClient().client();
        client.disable();
        assertFalse(client.isActive());
    }

    @Test
    void isActive_softDeleted_returnsFalse() {
        var client = createTestClient().client();
        client.softDelete();
        assertFalse(client.isActive());
    }

    // -- Disable / Enable --

    @Test
    void disable_enabledClient_setsEnabledFalse() {
        var client = createTestClient().client();
        client.disable();
        assertFalse(client.isEnabled());
    }

    @Test
    void disable_softDeletedClient_throwsIllegalState() {
        var client = createTestClient().client();
        client.softDelete();
        assertThrows(IllegalStateException.class, client::disable);
    }

    @Test
    void enable_disabledClient_setsEnabledTrue() {
        var client = createTestClient().client();
        client.disable();
        client.enable();
        assertTrue(client.isEnabled());
        assertTrue(client.isActive());
    }

    @Test
    void enable_softDeletedClient_throwsIllegalState() {
        var client = createTestClient().client();
        client.softDelete();
        assertThrows(IllegalStateException.class, client::enable);
    }

    // -- Soft delete --

    @Test
    void softDelete_activeClient_setsDeletedAtAndDisables() {
        var client = createTestClient().client();
        client.softDelete();
        assertNotNull(client.getDeletedAt());
        assertFalse(client.isEnabled());
        assertFalse(client.isActive());
    }

    @Test
    void softDelete_alreadyDeleted_throwsIllegalState() {
        var client = createTestClient().client();
        client.softDelete();
        assertThrows(IllegalStateException.class, client::softDelete);
    }

    // -- Secret rotation --

    @Test
    void rotateSecret_returnsNewSecretAndInvalidatesOld() {
        var result = createTestClient();
        String oldSecret = result.plaintextSecret();

        String newSecret = result.client().rotateSecret();
        assertNotNull(newSecret);
        assertNotEquals(oldSecret, newSecret);

        // Old secret no longer works
        assertFalse(result.client().validateCredentials(oldSecret));
        // New secret works
        assertTrue(result.client().validateCredentials(newSecret));
    }

    @Test
    void rotateSecret_softDeletedClient_throwsIllegalState() {
        var client = createTestClient().client();
        client.softDelete();
        assertThrows(IllegalStateException.class, client::rotateSecret);
    }

    // -- Scope replacement --

    @Test
    void replaceScopes_replacesEntireSet() {
        var client = createTestClient().client();
        assertEquals(2, client.getScopeNames().size());

        client.replaceScopes(Set.of("invoices:read", "invoices:write", "admin:*"));
        assertEquals(Set.of("invoices:read", "invoices:write", "admin:*"), client.getScopeNames());
    }

    @Test
    void replaceScopes_nullSet_throwsNPE() {
        var client = createTestClient().client();
        assertThrows(NullPointerException.class, () -> client.replaceScopes(null));
    }

    // -- Update metadata --

    @Test
    void updateMetadata_updatesNonNullFields() {
        var client = createTestClient().client();
        client.updateMetadata("New Name", "New Desc", 7200);
        assertEquals("New Name", client.getName());
        assertEquals("New Desc", client.getDescription());
        assertEquals(7200, client.getTokenTtlSeconds());
    }

    @Test
    void updateMetadata_nullFieldsAreSkipped() {
        var client = createTestClient().client();
        client.updateMetadata(null, null, null);
        assertEquals("Test Client", client.getName());
        assertEquals("desc", client.getDescription());
        assertEquals(3600, client.getTokenTtlSeconds());
    }

    // -- Equals / hashCode --

    @Test
    void equals_sameUuid_areEqual() {
        var c1 = createTestClient().client();
        var c2 = createTestClient().client();
        assertNotEquals(c1, c2); // different UUIDs

        // same object
        assertEquals(c1, c1);
    }
}
