package dk.trustworks.intranet.security.apiclient;

import dk.trustworks.intranet.security.apiclient.model.ApiClient;
import dk.trustworks.intranet.security.apiclient.model.ApiClientAuditLog;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the ApiClient aggregate root.
 * Also provides persistence for the append-only audit log,
 * since audit entries reference clients by UUID (not via aggregate navigation).
 */
@ApplicationScoped
public class ApiClientRepository implements PanacheRepositoryBase<ApiClient, String> {

    @Inject
    EntityManager entityManager;

    public Optional<ApiClient> findByUuid(String uuid) {
        return find("uuid", uuid).firstResultOptional();
    }

    /**
     * Looks up a client by its human-readable client_id.
     * Returns the client regardless of enabled/deleted state
     * so the caller can distinguish "not found" from "disabled".
     */
    public Optional<ApiClient> findByClientId(String clientId) {
        return find("clientId", clientId).firstResultOptional();
    }

    /**
     * Fetches a client with its scopes eagerly loaded in a single query.
     */
    public Optional<ApiClient> findByClientIdWithScopes(String clientId) {
        return find("""
                SELECT DISTINCT c FROM ApiClient c
                LEFT JOIN FETCH c.scopes
                WHERE c.clientId = ?1
                """, clientId).firstResultOptional();
    }

    public Optional<ApiClient> findByUuidWithScopes(String uuid) {
        return find("""
                SELECT DISTINCT c FROM ApiClient c
                LEFT JOIN FETCH c.scopes
                WHERE c.uuid = ?1
                """, uuid).firstResultOptional();
    }

    /**
     * Lists all non-soft-deleted clients (with scopes eagerly loaded).
     */
    public List<ApiClient> findAllActive() {
        return find("""
                SELECT DISTINCT c FROM ApiClient c
                LEFT JOIN FETCH c.scopes
                WHERE c.deletedAt IS NULL
                ORDER BY c.createdAt DESC
                """).list();
    }

    /**
     * Persists an audit log entry. This is append-only.
     */
    public void logAudit(ApiClientAuditLog entry) {
        entityManager.persist(entry);
    }

    /**
     * Retrieves audit log entries for a specific client, newest first.
     */
    public List<ApiClientAuditLog> findAuditLogByClientUuid(String clientUuid) {
        return entityManager.createQuery("""
                SELECT a FROM ApiClientAuditLog a
                WHERE a.clientUuid = :clientUuid
                ORDER BY a.createdAt DESC
                """, ApiClientAuditLog.class)
                .setParameter("clientUuid", clientUuid)
                .getResultList();
    }
}
