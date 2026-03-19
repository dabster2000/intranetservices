package dk.trustworks.intranet.security.apiclient;

import dk.trustworks.intranet.security.apiclient.dto.AuditLogEntryResponse;
import dk.trustworks.intranet.security.apiclient.model.ApiClient;
import dk.trustworks.intranet.security.apiclient.model.ApiClientAuditLog;
import dk.trustworks.intranet.security.apiclient.model.AuditEventType;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
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

    /**
     * Paginated audit log query with optional filters.
     * Resolves client_id from the ApiClient aggregate via LEFT JOIN.
     *
     * @param clientUuid filter by client UUID (null = all clients)
     * @param eventTypes filter by event types (null/empty = all types)
     * @param from       minimum created_at (inclusive, null = no lower bound)
     * @param to         maximum created_at (inclusive, null = no upper bound)
     * @param page       zero-based page index
     * @param size       page size
     * @return list of audit log entry DTOs with resolved clientId
     */
    public List<AuditLogEntryResponse> findAuditLogPaginated(
            String clientUuid,
            List<AuditEventType> eventTypes,
            LocalDateTime from,
            LocalDateTime to,
            int page,
            int size) {

        var jpql = buildAuditLogJpql(false, clientUuid, eventTypes, from, to);

        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class);
        bindAuditLogParams(query, clientUuid, eventTypes, from, to);
        query.setFirstResult(page * size);
        query.setMaxResults(size);

        return query.getResultList().stream()
                .map(row -> new AuditLogEntryResponse(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        (String) row[2],     // resolved client_id (may be null if client deleted)
                        ((AuditEventType) row[3]).name(),
                        (String) row[4],
                        (String) row[5],
                        (LocalDateTime) row[6]
                ))
                .toList();
    }

    /**
     * Count of audit log entries matching the given filters.
     */
    public long countAuditLog(
            String clientUuid,
            List<AuditEventType> eventTypes,
            LocalDateTime from,
            LocalDateTime to) {

        var jpql = buildAuditLogJpql(true, clientUuid, eventTypes, from, to);

        TypedQuery<Long> query = entityManager.createQuery(jpql, Long.class);
        bindAuditLogParams(query, clientUuid, eventTypes, from, to);
        return query.getSingleResult();
    }

    private String buildAuditLogJpql(
            boolean countOnly,
            String clientUuid,
            List<AuditEventType> eventTypes,
            LocalDateTime from,
            LocalDateTime to) {

        var sb = new StringBuilder();
        if (countOnly) {
            sb.append("SELECT COUNT(a) FROM ApiClientAuditLog a LEFT JOIN ApiClient c ON a.clientUuid = c.uuid");
        } else {
            sb.append("SELECT a.id, a.clientUuid, c.clientId, a.eventType, a.ipAddress, a.details, a.createdAt ");
            sb.append("FROM ApiClientAuditLog a LEFT JOIN ApiClient c ON a.clientUuid = c.uuid");
        }

        sb.append(" WHERE 1=1");
        if (clientUuid != null) {
            sb.append(" AND a.clientUuid = :clientUuid");
        }
        if (eventTypes != null && !eventTypes.isEmpty()) {
            sb.append(" AND a.eventType IN :eventTypes");
        }
        if (from != null) {
            sb.append(" AND a.createdAt >= :fromDate");
        }
        if (to != null) {
            sb.append(" AND a.createdAt <= :toDate");
        }

        if (!countOnly) {
            sb.append(" ORDER BY a.createdAt DESC");
        }
        return sb.toString();
    }

    private void bindAuditLogParams(
            TypedQuery<?> query,
            String clientUuid,
            List<AuditEventType> eventTypes,
            LocalDateTime from,
            LocalDateTime to) {

        if (clientUuid != null) {
            query.setParameter("clientUuid", clientUuid);
        }
        if (eventTypes != null && !eventTypes.isEmpty()) {
            query.setParameter("eventTypes", eventTypes);
        }
        if (from != null) {
            query.setParameter("fromDate", from);
        }
        if (to != null) {
            query.setParameter("toDate", to);
        }
    }
}
