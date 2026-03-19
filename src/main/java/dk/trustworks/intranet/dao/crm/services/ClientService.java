package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Clientdata;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class ClientService {

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
}