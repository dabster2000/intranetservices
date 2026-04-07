package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Clientdata;
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

    public List<Client> listAllClients() {
        return Client.listAll(Sort.ascending("name"));
    }

    public Client findByUuid(String uuid) {
        return Client.findById(uuid);
    }

    public List<Client> findByActiveTrue() {
        return Client.list("active = ?1", Sort.ascending("name"), true);
    }

    @Transactional
    public Client save(Client client) {
        String userUuid = requestHeaderHolder != null ? requestHeaderHolder.getUserUuid() : null;
        client.setUuid(UUID.randomUUID().toString());
        if(client.getManaged() == null || client.getManaged().isBlank()) {
            log.warnf("Client managed field is blank for new client name=%s, defaulting to INTRA, user=%s",
                    client.getName(), userUuid);
            client.setManaged("INTRA");
        }
        client.persist();
        log.infof("Created client uuid=%s, name=%s, active=%s, user=%s",
                client.getUuid(), client.getName(), client.isActive(), userUuid);
        return client;
    }

    @Transactional
    public void updateOne(Client client) {
        String userUuid = requestHeaderHolder != null ? requestHeaderHolder.getUserUuid() : null;
        log.infof("Updating client uuid=%s, name=%s, active=%s, user=%s",
                client.getUuid(), client.getName(), client.isActive(), userUuid);
        Client.update("active = ?1, " +
                        "contactname = ?2, " +
                        "name = ?3, " +
                        "accountmanager = ?4, " +
                        "crmid = ?5, " +
                        "segment = ?6, " +
                        "managed = ?7, " +
                        "cvr = ?8, " +
                        "ean = ?9, " +
                        "billingAddress = ?10, " +
                        "billingZipcode = ?11, " +
                        "billingCity = ?12, " +
                        "billingCountry = ?13, " +
                        "billingEmail = ?14, " +
                        "currency = ?15, " +
                        "phone = ?16, " +
                        "industryCode = ?17, " +
                        "industryDesc = ?18, " +
                        "companyCode = ?19, " +
                        "companyDesc = ?20 " +
                        "WHERE uuid like ?21 ",
                client.isActive(), client.getContactname(),
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
                client.getUuid());
    }

    @Transactional
    public void updateActiveStatus(String uuid, boolean active) {
        Client.update("active = ?1 WHERE uuid = ?2", active, uuid);
    }

    public Client findByCvr(String cvr) {
        return Client.find("cvr", cvr).firstResult();
    }

    public Client findByExactNameIgnoreCase(String name) {
        return Client.find("LOWER(name) = LOWER(?1)", name).firstResult();
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
            return Client.list("LOWER(name) LIKE LOWER(?1) ORDER BY " +
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

    public List<Clientdata> listAllClientData(String clientuuid) {
        return Clientdata.stream("clientuuid", Sort.ascending("contactperson"), clientuuid).map(p -> (Clientdata) p).toList();
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