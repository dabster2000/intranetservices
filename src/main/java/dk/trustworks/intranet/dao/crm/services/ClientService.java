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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
                        "managed = ?7 " +
                        "WHERE uuid like ?8 ",
                client.isActive(), client.getContactname(),
                client.getName(), client.getAccountmanager(),
                client.getCrmid(), client.getSegment(),
                client.getManaged(),
                client.getUuid());
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