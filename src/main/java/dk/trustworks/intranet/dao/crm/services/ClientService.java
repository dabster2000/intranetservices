package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Clientdata;
import io.quarkus.panache.common.Sort;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ClientService {

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
    public void save(Client client) {
        client.setUuid(UUID.randomUUID().toString());
        client.persist();
    }

    @Transactional
    public void updateOne(Client client) {
        Client.update("active = ?1, " +
                        "contactname = ?2, " +
                        "name = ?3, " +
                        "accountmanager = ?4, " +
                        "crmid = ?5, " +
                        "segment = ?6 " +
                        "WHERE uuid like ?7 ",
                client.isActive(), client.getContactname(),
                client.getName(), client.getAccountmanager(),
                client.getCrmid(), client.getSegment(),
                client.getUuid());
    }

    public List<Clientdata> listAllClientData(String clientuuid) {
        return Clientdata.stream("clientuuid", Sort.ascending("contactperson"), clientuuid).map(p -> (Clientdata) p).toList();
    }
}