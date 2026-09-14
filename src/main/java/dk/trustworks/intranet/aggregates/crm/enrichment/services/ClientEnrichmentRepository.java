package dk.trustworks.intranet.aggregates.crm.enrichment.services;

import dk.trustworks.intranet.aggregates.crm.enrichment.model.ClientEnrichment;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.CvrEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.LogoEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.SectorEnrichmentStatus;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The {@code client_enrichment} rows: who is due tonight, and the one-row-per-client
 * bookkeeping every job writes.
 *
 * <p><b>Rows are seeded from the client's own state, not blank.</b> A client that already
 * carries a CVR and registry industry data has plainly had a lookup — the form only fills
 * those fields from one — so it starts {@code VERIFIED} without spending a registry call.
 * A non-Danish row starts {@code SKIPPED} (Virkdata covers Denmark only), a partner or
 * prospect starts with its logo {@code SKIPPED} (clients only, by decision), and a client
 * already filed under a real sector starts {@code HUMAN_SET}. {@link #initialState} is the
 * Java form of that rule and {@link #seedMissingRows} the SQL form; the fast tier pins the
 * Java one and the two are kept side by side so a change to one is a change to both.
 *
 * <p>Every method that writes opens its own transaction. The jobs call these between
 * model and registry round-trips and must never hold a pooled connection across one.
 */
@JBossLog
@ApplicationScoped
public class ClientEnrichmentRepository {

    @Inject
    EntityManager em;

    // ------------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------------

    /** What a client's row says before any job has touched it. Pure. */
    public static ClientEnrichment initialState(Client client) {
        ClientEnrichment row = new ClientEnrichment(client.getUuid());
        boolean danish = client.getBillingCountry() == null || client.getBillingCountry().isBlank()
                || "DK".equalsIgnoreCase(client.getBillingCountry().trim());
        boolean hasCvr = client.getCvr() != null && !client.getCvr().isBlank();
        boolean lookedUp = hasCvr && client.getIndustryCode() != null && client.getIndustryCode() > 0;
        row.setCvr(!danish ? CvrEnrichmentStatus.SKIPPED
                : lookedUp ? CvrEnrichmentStatus.VERIFIED
                : CvrEnrichmentStatus.PENDING);
        row.setLogo(client.getType() == ClientType.CLIENT ? LogoEnrichmentStatus.PENDING : LogoEnrichmentStatus.SKIPPED);
        row.setSector(client.getSegment() == null || client.getSegment() == ClientSegment.OTHER
                ? SectorEnrichmentStatus.PENDING
                : SectorEnrichmentStatus.HUMAN_SET);
        return row;
    }

    /**
     * One statement that gives every client without a row its seeded row. Idempotent; run
     * at the start of every nightly pass so the eligibility queries below can read the
     * table alone.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int seedMissingRows() {
        return em.createNativeQuery("""
                INSERT INTO client_enrichment (client_uuid, cvr_status, logo_status, sector_status)
                SELECT c.uuid,
                       CASE WHEN c.billing_country IS NOT NULL AND c.billing_country <> '' AND UPPER(c.billing_country) <> 'DK' THEN 'SKIPPED'
                            WHEN c.cvr IS NOT NULL AND c.cvr <> '' AND c.industry_code IS NOT NULL AND c.industry_code > 0 THEN 'VERIFIED'
                            ELSE 'PENDING' END,
                       CASE WHEN c.type = 'CLIENT' THEN 'PENDING' ELSE 'SKIPPED' END,
                       CASE WHEN c.segment IS NULL OR c.segment = 'OTHER' THEN 'PENDING' ELSE 'HUMAN_SET' END
                FROM client c
                LEFT JOIN client_enrichment e ON e.client_uuid = c.uuid
                WHERE e.client_uuid IS NULL
                """).executeUpdate();
    }

    /** The row for one client, created from {@link #initialState} if missing. Joins the caller's transaction. */
    @Transactional(Transactional.TxType.REQUIRED)
    public ClientEnrichment ensure(String clientUuid) {
        ClientEnrichment existing = ClientEnrichment.findById(clientUuid);
        if (existing != null) return existing;
        Client client = Client.findById(clientUuid);
        if (client == null) return null;
        ClientEnrichment row = initialState(client);
        row.persist();
        return row;
    }

    // ------------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------------

    public Optional<ClientEnrichment> find(String clientUuid) {
        return ClientEnrichment.findByIdOptional(clientUuid);
    }

    public List<ClientEnrichment> findAll() {
        return ClientEnrichment.listAll();
    }

    // ------------------------------------------------------------------------
    // Tonight's queues
    // ------------------------------------------------------------------------

    /**
     * Danish rows whose CVR question is open: never checked, or a failed lookup old enough
     * to try again. The terminal answers — CANDIDATE, NOT_FOUND, INVALID, DUPLICATE,
     * DISMISSED — wait for a person. Oldest attempt first, newest client first among the
     * untouched, so a client added yesterday is verified before one added in 2019.
     */
    @SuppressWarnings("unchecked")
    public List<String> eligibleForCvr(int limit, LocalDateTime retryBefore) {
        if (limit <= 0) return List.of();
        return em.createNativeQuery("""
                SELECT e.client_uuid
                FROM client_enrichment e
                JOIN client c ON c.uuid = e.client_uuid
                WHERE e.cvr_status = 'PENDING'
                   OR (e.cvr_status = 'FAILED' AND (e.cvr_checked_at IS NULL OR e.cvr_checked_at < :retryBefore))
                ORDER BY COALESCE(e.cvr_checked_at, '1970-01-01'), c.created DESC, c.uuid
                LIMIT :limit
                """)
                .setParameter("retryBefore", retryBefore)
                .setParameter("limit", limit)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<String> eligibleForLogo(int limit, LocalDateTime retryBefore) {
        if (limit <= 0) return List.of();
        return em.createNativeQuery("""
                SELECT e.client_uuid
                FROM client_enrichment e
                JOIN client c ON c.uuid = e.client_uuid
                WHERE c.type = 'CLIENT'
                  AND (e.logo_status = 'PENDING'
                       OR (e.logo_status = 'FAILED' AND (e.logo_checked_at IS NULL OR e.logo_checked_at < :retryBefore)))
                ORDER BY COALESCE(e.logo_checked_at, '1970-01-01'), c.created DESC, c.uuid
                LIMIT :limit
                """)
                .setParameter("retryBefore", retryBefore)
                .setParameter("limit", limit)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<String> eligibleForSector(int limit, LocalDateTime retryBefore) {
        if (limit <= 0) return List.of();
        return em.createNativeQuery("""
                SELECT e.client_uuid
                FROM client_enrichment e
                JOIN client c ON c.uuid = e.client_uuid
                WHERE (c.segment IS NULL OR c.segment = 'OTHER')
                  AND (e.sector_status = 'PENDING'
                       OR (e.sector_status = 'FAILED' AND (e.sector_checked_at IS NULL OR e.sector_checked_at < :retryBefore)))
                ORDER BY COALESCE(e.sector_checked_at, '1970-01-01'), c.created DESC, c.uuid
                LIMIT :limit
                """)
                .setParameter("retryBefore", retryBefore)
                .setParameter("limit", limit)
                .getResultList();
    }

    /** Another client already carrying this CVR, if any. */
    public Optional<Client> otherClientWithCvr(String cvr, String exceptClientUuid) {
        if (cvr == null || cvr.isBlank()) return Optional.empty();
        return Client.find("cvr = ?1 and uuid <> ?2", cvr.trim(), exceptClientUuid).firstResultOptional();
    }
}
