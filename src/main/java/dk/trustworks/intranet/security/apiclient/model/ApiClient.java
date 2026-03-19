package dk.trustworks.intranet.security.apiclient.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.mindrot.jbcrypt.BCrypt;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Aggregate root for the API Client bounded context.
 *
 * An ApiClient represents a registered machine-to-machine consumer
 * that can obtain short-lived JWT tokens via the /auth/token endpoint.
 * All business invariants for client lifecycle (create, disable, enable,
 * soft-delete, credential validation, secret rotation, scope management)
 * are enforced here.
 *
 * Cross-aggregate references (e.g., to users) use UUID strings,
 * not entity relationships.
 */
@Entity
@Table(name = "api_clients")
@Getter
@NoArgsConstructor
public class ApiClient {

    private static final int BCRYPT_COST = 12;
    private static final int SECRET_BYTE_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    @Column(name = "client_secret_hash", nullable = false, length = 255)
    private String clientSecretHash;

    @Column(name = "name", nullable = false, length = 255)
    @Setter
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    @Setter
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "token_ttl_seconds", nullable = false)
    @Setter
    private int tokenTtlSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ApiClientScope> scopes = new ArrayList<>();

    // -- Factory method --

    /**
     * Creates a new API client with a generated UUID and hashed secret.
     *
     * @return a record containing the persisted entity and the plaintext secret (shown once)
     */
    public static CreationResult create(String clientId, String name, String description,
                                         int tokenTtlSeconds, Set<String> scopes, String createdBy) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");

        var client = new ApiClient();
        client.uuid = UUID.randomUUID().toString();
        client.clientId = clientId;
        client.name = name;
        client.description = description;
        client.tokenTtlSeconds = tokenTtlSeconds > 0 ? tokenTtlSeconds : 3600;
        client.enabled = true;
        client.createdBy = createdBy;
        client.createdAt = LocalDateTime.now();
        client.updatedAt = client.createdAt;

        String plaintextSecret = generateSecret();
        client.clientSecretHash = BCrypt.hashpw(plaintextSecret, BCrypt.gensalt(BCRYPT_COST));

        if (scopes != null) {
            for (String scope : scopes) {
                client.scopes.add(new ApiClientScope(client, scope));
            }
        }

        return new CreationResult(client, plaintextSecret);
    }

    // -- Business logic --

    /**
     * Validates the provided plaintext secret against the stored hash.
     * Uses bcrypt's constant-time comparison.
     *
     * @return true if the secret matches
     */
    public boolean validateCredentials(String plaintextSecret) {
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            return false;
        }
        return BCrypt.checkpw(plaintextSecret, this.clientSecretHash);
    }

    /**
     * Returns true if this client can issue tokens:
     * enabled and not soft-deleted.
     */
    public boolean isActive() {
        return this.enabled && this.deletedAt == null;
    }

    /**
     * Temporarily disables the client. New token requests will be rejected
     * with 403. Can be re-enabled.
     *
     * @throws IllegalStateException if already soft-deleted
     */
    public void disable() {
        if (this.deletedAt != null) {
            throw new IllegalStateException("Cannot disable a soft-deleted client: " + this.uuid);
        }
        this.enabled = false;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Re-enables a previously disabled client.
     *
     * @throws IllegalStateException if soft-deleted (permanent)
     */
    public void enable() {
        if (this.deletedAt != null) {
            throw new IllegalStateException("Cannot enable a soft-deleted client: " + this.uuid);
        }
        this.enabled = true;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Permanently decommissions the client by setting deleted_at.
     * This is irreversible. The client cannot be re-enabled.
     *
     * @throws IllegalStateException if already soft-deleted
     */
    public void softDelete() {
        if (this.deletedAt != null) {
            throw new IllegalStateException("Client already soft-deleted: " + this.uuid);
        }
        this.deletedAt = LocalDateTime.now();
        this.enabled = false;
        this.updatedAt = this.deletedAt;
    }

    /**
     * Generates a new secret, replaces the stored hash, and returns
     * the plaintext secret exactly once.
     *
     * @throws IllegalStateException if the client is soft-deleted
     */
    public String rotateSecret() {
        if (this.deletedAt != null) {
            throw new IllegalStateException("Cannot rotate secret for soft-deleted client: " + this.uuid);
        }
        String plaintextSecret = generateSecret();
        this.clientSecretHash = BCrypt.hashpw(plaintextSecret, BCrypt.gensalt(BCRYPT_COST));
        this.updatedAt = LocalDateTime.now();
        return plaintextSecret;
    }

    /**
     * Replaces the entire set of scopes with the provided set.
     * Uses orphan removal to clean up removed scopes.
     */
    public void replaceScopes(Set<String> newScopes) {
        Objects.requireNonNull(newScopes, "scopes must not be null");
        this.scopes.clear();
        for (String scope : newScopes) {
            this.scopes.add(new ApiClientScope(this, scope));
        }
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Returns an unmodifiable set of scope strings.
     */
    public Set<String> getScopeNames() {
        var result = new LinkedHashSet<String>();
        for (ApiClientScope s : this.scopes) {
            result.add(s.getScope());
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Updates mutable metadata fields (name, description, tokenTtlSeconds).
     */
    public void updateMetadata(String name, String description, Integer tokenTtlSeconds) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (tokenTtlSeconds != null && tokenTtlSeconds > 0) {
            this.tokenTtlSeconds = tokenTtlSeconds;
        }
        this.updatedAt = LocalDateTime.now();
    }

    // -- Helpers --

    private static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiClient other)) return false;
        return uuid != null && uuid.equals(other.uuid);
    }

    @Override
    public int hashCode() {
        return uuid != null ? uuid.hashCode() : 0;
    }

    // -- Inner types --

    /**
     * Returned from {@link #create} to expose the plaintext secret exactly once.
     */
    public record CreationResult(ApiClient client, String plaintextSecret) {}
}
