package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import dk.trustworks.intranet.utils.StringSimilarity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class ClientService {

    @Inject
    EntityManager em;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * Every client that still is one. A row merged into another
     * ({@link Client#NOT_MERGED}) is left out here and in every other listing, search and
     * dedup lookup below: a tombstone is not a client anybody may pick, and a name match
     * against one would quietly revive a company that was put back together on purpose.
     */
    public List<Client> listAllClients() {
        return Client.list(Client.NOT_MERGED, Sort.ascending("name"));
    }

    /**
     * By uuid, INCLUDING a merged row. This is the one read that must still answer for a
     * tombstone: a bookmarked account page or a cached uuid gets the row back with
     * {@code mergedIntoUuid} set, and the page forwards. Every listing goes through the
     * filtered methods instead.
     */
    public Client findByUuid(String uuid) {
        return Client.findById(uuid);
    }

    /**
     * Lists clients filtered by type.
     */
    public List<Client> listByType(ClientType type) {
        return Client.list("type = ?1 and " + Client.NOT_MERGED, Sort.ascending("name"), type);
    }

    /**
     * Lists clients of any of several types.
     *
     * <p>Exists because {@code PROSPECT} (2026-09-14) is a value most callers must keep
     * excluding — the invoice picker, devices, the timesheet chain — while a few need it
     * beside {@code CLIENT}: the accounts list, the lead form's client picker and the
     * signal box. Every one of those is an explicit opt-in at the call site, which is the
     * whole reason prospects were made a third {@code type} rather than a flag.
     *
     * <p>An empty or null list answers with nothing rather than with everything: a filter
     * that silently widens is how a prospect ends up in an invoice.
     */
    public List<Client> listByTypes(java.util.Collection<ClientType> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        return Client.list("type in ?1 and " + Client.NOT_MERGED, Sort.ascending("name"), List.copyOf(types));
    }

    @Transactional
    public Client save(Client client) {
        String userUuid = requestHeaderHolder != null ? requestHeaderHolder.getUserUuid() : null;
        client.setUuid(UUID.randomUUID().toString());
        // The tombstone is written by ClientMergeService alone. The entity is the request
        // body here, so a caller could otherwise create a client already merged away.
        client.setMergedIntoUuid(null);
        client.setMergedAt(null);
        if(client.getManaged() == null || client.getManaged().isBlank()) {
            log.warnf("Client managed field is blank for new client name=%s, defaulting to INTRA, user=%s",
                    client.getName(), userUuid);
            client.setManaged("INTRA");
        }
        client.persist();
        log.infof("Created client uuid=%s, name=%s, user=%s",
                client.getUuid(), client.getName(), userUuid);
        return client;
    }

    @Transactional
    public void updateOne(Client client) {
        String userUuid = requestHeaderHolder != null ? requestHeaderHolder.getUserUuid() : null;
        ClientType incomingType = client.getType() != null ? client.getType() : ClientType.CLIENT;
        log.infof("Updating client uuid=%s, name=%s, type=%s, user=%s",
                client.getUuid(), client.getName(), incomingType, userUuid);
        Client.update("contactname = ?1, " +
                        "name = ?2, " +
                        "accountmanager = ?3, " +
                        "crmid = ?4, " +
                        "segment = ?5, " +
                        "managed = ?6, " +
                        "cvr = ?7, " +
                        "ean = ?8, " +
                        "billingAddress = ?9, " +
                        "billingZipcode = ?10, " +
                        "billingCity = ?11, " +
                        "billingCountry = ?12, " +
                        "billingEmail = ?13, " +
                        "currency = ?14, " +
                        "phone = ?15, " +
                        "industryCode = ?16, " +
                        "industryDesc = ?17, " +
                        "companyCode = ?18, " +
                        "companyDesc = ?19, " +
                        "type = ?20, " +
                        "defaultBillingAttention = ?21, " +
                        "defaultBillingEmail = ?22 " +
                        "WHERE uuid like ?23 ",
                client.getContactname(),
                client.getName(), client.getAccountmanager(),
                client.getCrmid(), client.getSegment(),
                client.getManaged(),
                client.getCvr(), client.getEan(),
                client.getBillingAddress(), client.getBillingZipcode(),
                client.getBillingCity(), client.getBillingCountry(),
                client.getBillingEmail(), client.getCurrency(),
                client.getPhone(), client.getIndustryCode(),
                client.getIndustryDesc(), client.getCompanyCode(),
                client.getCompanyDesc(),
                incomingType,
                client.getDefaultBillingAttention(),
                client.getDefaultBillingEmail(),
                client.getUuid());
    }

    public Client findByCvr(String cvr) {
        return Client.find("cvr = ?1 and " + Client.NOT_MERGED, cvr).firstResult();
    }

    public Client findByExactNameIgnoreCase(String name) {
        return Client.find("LOWER(name) = LOWER(?1) and " + Client.NOT_MERGED, name).firstResult();
    }

    /**
     * Jaro-Winkler similarity threshold for fuzzy name matching.
     * 0.90 catches typos, spacing, and minor punctuation differences
     * while rejecting genuinely different names.
     */
    private static final double FUZZY_MATCH_THRESHOLD = 0.90;

    /**
     * Finds an existing client whose name matches the given name either exactly
     * (case-insensitive) or via Jaro-Winkler fuzzy similarity above the threshold.
     * <p>
     * The exact match is checked first (via DB query) for efficiency. If no exact
     * match is found, all client names are compared using Jaro-Winkler, and the
     * best match above the threshold is returned.
     *
     * @param name the candidate client name to match against
     * @return the best matching client, or empty if no match meets the threshold
     */
    private static final int MAX_NAME_LENGTH = 255;

    public Optional<Client> findFuzzyMatch(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            return Optional.empty();
        }

        String normalized = name.trim().toLowerCase();

        // Fast path: exact case-insensitive match via DB query
        Client exactMatch = findByExactNameIgnoreCase(normalized);
        if (exactMatch != null) {
            return Optional.of(exactMatch);
        }

        // Slow path: Jaro-Winkler comparison against all clients
        List<Client> allClients = listAllClients();
        Client bestMatch = null;
        double bestScore = 0.0;

        for (Client client : allClients) {
            if (client.getName() == null) continue;
            String candidateNormalized = client.getName().trim().toLowerCase();
            double score = StringSimilarity.jaroWinkler(normalized, candidateNormalized);
            if (score >= FUZZY_MATCH_THRESHOLD && score > bestScore) {
                bestScore = score;
                bestMatch = client;
            }
        }

        if (bestMatch != null) {
            log.infof("Fuzzy match found: input='%s' matched client uuid=%s name='%s' score=%.4f",
                    name, bestMatch.getUuid(), bestMatch.getName(), bestScore);
        }

        return Optional.ofNullable(bestMatch);
    }

    public List<Client> searchClients(String cvr, String name) {
        if (cvr != null && !cvr.isBlank()) {
            Client match = findByCvr(cvr.trim());
            return match != null ? List.of(match) : List.of();
        }
        if (name != null && !name.isBlank()) {
            String sanitized = escapeLikeWildcards(name.trim());
            return Client.list("LOWER(name) LIKE LOWER(?1) and " + Client.NOT_MERGED + " ORDER BY " +
                    "CASE WHEN LOWER(name) = LOWER(?2) THEN 0 ELSE 1 END, name",
                    "%" + sanitized + "%", name.trim());
        }
        return List.of();
    }

    /**
     * Escapes SQL LIKE wildcard characters (%, _) in a search term
     * to prevent wildcard injection.
     */
    private static String escapeLikeWildcards(String input) {
        return input.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getContractCounts() {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT c.clientuuid, COUNT(*) AS total, " +
                "SUM(CASE WHEN c.status IN ('BUDGET', 'TIME', 'SIGNED') THEN 1 ELSE 0 END) AS active " +
                "FROM contracts c GROUP BY c.clientuuid")
                .getResultList();
        return rows.stream().map(row -> Map.<String, Object>of(
                "clientUuid", (String) row[0],
                "total", (Number) row[1],
                "active", (Number) row[2]
        )).toList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getClientConsultants(LocalDate fromDate, LocalDate toDate) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT DISTINCT c.clientuuid, cc.useruuid, " +
                "COALESCE(cc.name, CONCAT(u.firstname, ' ', u.lastname)) as consultantName " +
                "FROM contracts c " +
                "JOIN contract_consultants cc ON c.uuid = cc.contractuuid " +
                "LEFT JOIN user u ON cc.useruuid = u.uuid " +
                "WHERE c.status IN ('BUDGET', 'TIME', 'SIGNED') " +
                "AND cc.activefrom <= ?1 " +
                "AND cc.activeto >= ?2")
                .setParameter(1, toDate)
                .setParameter(2, fromDate)
                .getResultList();
        return rows.stream().map(row -> Map.of(
                "clientUuid", (String) row[0],
                "userUuid", (String) row[1],
                "consultantName", row[2] != null ? (String) row[2] : "Unknown"
        )).toList();
    }
}