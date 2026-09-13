package dk.trustworks.intranet.aggregates.crm.news;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.dao.crm.model.Client;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.time.*;
import java.util.*;

import static jakarta.transaction.Transactional.TxType.REQUIRES_NEW;

/** Only short database transactions. The caller performs all HTTP outside this bean. */
@ApplicationScoped
public class ClientNewsRepository {
    @Inject EntityManager em;
    @Inject ObjectMapper mapper;
    public record Identity(String uuid, String name, String cvr) { }
    public record Snapshot(boolean eligible, ClientNewsDTO.Status status, boolean coverageLimited, Instant attempted,
                           Instant succeeded, List<ClientNewsDTO.StoredItem> items) { }
    public record Lease(Identity client, String token, Instant checkedThrough,
                        List<ClientNewsDTO.StoredItem> items) { }

    @Transactional(REQUIRES_NEW)
    public List<Identity> eligibleClients() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.uuid, c.name, c.cvr FROM client c
                JOIN client_plan p ON p.client_uuid=c.uuid AND p.status='ACTIVE'
                LEFT JOIN client_news_state n ON n.client_uuid=c.uuid
                WHERE c.type='CLIENT' ORDER BY COALESCE(n.last_successful_check_at, '1970-01-01'), c.uuid
                """).getResultList();
        return rows.stream().map(r -> new Identity((String) r[0], (String) r[1], (String) r[2])).toList();
    }

    @Transactional(REQUIRES_NEW)
    public Snapshot read(String uuid) {
        if (em.find(Client.class, uuid) == null) throw new NotFoundException("Client not found");
        boolean eligible = active(uuid);
        ClientNewsState state = em.find(ClientNewsState.class, uuid);
        if (state == null) return new Snapshot(eligible, ClientNewsDTO.Status.PENDING, false, null, null, List.of());
        return new Snapshot(eligible, ClientNewsDTO.Status.valueOf(state.status), "COVERAGE_LIMITED".equals(state.failureCode), instant(state.lastAttemptAt),
                instant(state.lastSuccessfulCheckAt), decode(state.itemsJson));
    }

    @Transactional(REQUIRES_NEW)
    public Optional<Lease> claim(Identity client, Instant dueAt, LocalDate day) {
        // Take an exclusive row lock immediately, including when the row already exists.
        // INSERT IGNORE followed by UPDATE can deadlock as concurrent shared locks upgrade.
        em.createNativeQuery("""
                INSERT INTO client_news_state (client_uuid) VALUES (:id)
                ON DUPLICATE KEY UPDATE client_uuid=VALUES(client_uuid)
                """)
                .setParameter("id", client.uuid()).executeUpdate();
        String token = UUID.randomUUID().toString();
        int won = em.createNativeQuery("""
                UPDATE client_news_state n SET
                    lease_token=:token, lease_until=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 MINUTE),
                    last_attempt_at=UTC_TIMESTAMP(6), status='PENDING', failure_code=NULL,
                    attempt_count=IF(attempt_day=:day, attempt_count+1, 1), attempt_day=:day
                WHERE client_uuid=:id AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6))
                  AND (last_successful_check_at IS NULL OR last_successful_check_at<:due)
                  AND (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP(6))
                  AND (attempt_day IS NULL OR attempt_day<>:day OR attempt_count<3)
                  AND EXISTS (SELECT 1 FROM client_plan p JOIN client c ON c.uuid=p.client_uuid
                              WHERE p.client_uuid=:id AND p.status='ACTIVE' AND c.type='CLIENT')
                """).setParameter("id", client.uuid()).setParameter("token", token)
                .setParameter("day", day).setParameter("due", utc(dueAt)).executeUpdate();
        if (won != 1) return Optional.empty();
        em.clear();
        ClientNewsState state = em.find(ClientNewsState.class, client.uuid());
        return Optional.of(new Lease(client, token, instant(state.checkedThrough), decode(state.itemsJson)));
    }

    /** Each actual HTTP attempt (including retries) consumes one durable daily unit. */
    @Transactional(REQUIRES_NEW)
    public boolean reserveRequest(Lease lease, LocalDate day, int limit) {
        if (!owns(lease)) return false;
        // Serialize contenders before the conditional increment, without a shared-lock upgrade.
        em.createNativeQuery("""
                INSERT INTO client_news_request_budget (budget_day) VALUES (:day)
                ON DUPLICATE KEY UPDATE budget_day=VALUES(budget_day)
                """)
                .setParameter("day", day).executeUpdate();
        return em.createNativeQuery("""
                UPDATE client_news_request_budget SET requests_used=requests_used+1
                WHERE budget_day=:day AND requests_used<:limit
                """).setParameter("day", day).setParameter("limit", limit).executeUpdate() == 1;
    }

    @Transactional(REQUIRES_NEW)
    public boolean complete(Lease lease, Instant through, List<ClientNewsDTO.StoredItem> items, boolean coverageLimited) {
        if (items.size() > 6) throw new IllegalArgumentException("At most six news items");
        String json;
        try { json = mapper.writeValueAsString(items); }
        catch (Exception e) { throw new IllegalStateException("NEWS_SERIALIZATION_FAILED"); }
        // The token fences a worker whose lease expired while an HTTP request was in flight.
        return em.createNativeQuery("""
                UPDATE client_news_state SET status='READY', items_json=:items,
                    checked_through=:through, last_successful_check_at=UTC_TIMESTAMP(6),
                    lease_token=NULL, lease_until=NULL, next_attempt_at=NULL, failure_code=:coverage
                WHERE client_uuid=:id AND lease_token=:token AND lease_until>UTC_TIMESTAMP(6)
                  AND EXISTS (SELECT 1 FROM client_plan p JOIN client c ON c.uuid=p.client_uuid
                              WHERE p.client_uuid=:id AND p.status='ACTIVE' AND c.type='CLIENT')
                """).setParameter("items", json).setParameter("through", utc(through))
                .setParameter("coverage", coverageLimited ? "COVERAGE_LIMITED" : null)
                .setParameter("id", lease.client.uuid()).setParameter("token", lease.token()).executeUpdate() == 1;
    }

    @Transactional(REQUIRES_NEW)
    public void fail(Lease lease, String code, List<ClientNewsDTO.StoredItem> partialItems) {
        // Error codes are controlled enums from the adapter, never provider messages/payloads.
        String partial = null;
        if (!partialItems.isEmpty()) {
            if (partialItems.size() > 6) throw new IllegalArgumentException("At most six news items");
            try { partial = mapper.writeValueAsString(partialItems); }
            catch (Exception e) { throw new IllegalStateException("NEWS_SERIALIZATION_FAILED"); }
        }
        em.createNativeQuery("""
                UPDATE client_news_state SET status='FAILED', failure_code=:code,
                    items_json=COALESCE(:items, items_json),
                    lease_token=NULL, lease_until=NULL,
                    next_attempt_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR)
                WHERE client_uuid=:id AND lease_token=:token AND lease_until>UTC_TIMESTAMP(6)
                """).setParameter("code", code).setParameter("items", partial).setParameter("id", lease.client.uuid())
                .setParameter("token", lease.token()).executeUpdate();
    }

    private boolean active(String uuid) {
        return ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM client_plan p JOIN client c ON c.uuid=p.client_uuid
                WHERE p.client_uuid=:id AND p.status='ACTIVE' AND c.type='CLIENT'
                """).setParameter("id", uuid).getSingleResult()).longValue() == 1;
    }
    private boolean owns(Lease lease) {
        return ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM client_news_state n JOIN client_plan p ON p.client_uuid=n.client_uuid
                JOIN client c ON c.uuid=p.client_uuid
                WHERE n.client_uuid=:id AND lease_token=:token AND lease_until>UTC_TIMESTAMP(6)
                    AND p.status='ACTIVE' AND c.type='CLIENT'
                """).setParameter("id", lease.client.uuid()).setParameter("token", lease.token())
                .getSingleResult()).longValue() == 1;
    }
    private List<ClientNewsDTO.StoredItem> decode(String json) {
        try {
            List<ClientNewsDTO.StoredItem> items = mapper.readValue(json, new TypeReference<>() { });
            return items.stream().limit(6).toList();
        } catch (Exception e) { throw new IllegalStateException("NEWS_CACHE_INVALID"); }
    }
    static LocalDateTime utc(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
}
