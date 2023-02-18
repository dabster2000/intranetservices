package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Clientdata;
import io.quarkus.panache.common.Sort;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.ws.rs.PathParam;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ClientService {

    public List<Client> listAll() {
        return Client.streamAll(Sort.ascending("name")).map(p -> (Client) p).collect(Collectors.toList());
    }

    public Client findByUuid(@PathParam("uuid") String uuid) {
        return Client.findById(uuid);
    }

    public List<Client> findByActiveTrue() {
        return Client.stream("active", Sort.ascending("name"), true).map(p -> (Client) p).collect(Collectors.toList());
    }

    public List<Clientdata> listAll(@PathParam("clientuuid") String clientuuid) {
        return Clientdata.stream("clientuuid", Sort.ascending("contactperson"), clientuuid).map(p -> (Clientdata) p).collect(Collectors.toList());
    }

    @Transactional
    public Client save(Client client) {
        client.setUuid(UUID.randomUUID().toString());
        client.persist();
        return client;
    }

    @Transactional
    public void updateOne(Client client) {
        Client.update("active = ?1, " +
                        "contactname = ?2, " +
                        "name = ?3, " +
                        "accountmanager = ?4, " +
                        "crmid = ?5 " +
                        "WHERE uuid like ?6 ",
                client.isActive(), client.getContactname(),
                client.getName(), client.getAccountmanager(),
                client.getCrmid(), client.getUuid());
    }


}