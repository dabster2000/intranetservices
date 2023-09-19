package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Clientdata;
import io.quarkus.panache.common.Sort;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ClientService {

    public List<Client> listAllClients() {
        return Client.listAll(Sort.ascending("name"));
        //return Client.streamAll(Sort.ascending("name")).map(p -> (Client) p).collect(Collectors.toList());
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
                        "crmid = ?5 " +
                        "WHERE uuid like ?6 ",
                client.isActive(), client.getContactname(),
                client.getName(), client.getAccountmanager(),
                client.getCrmid(), client.getUuid());
    }

    public List<Clientdata> listAllClientData(String clientuuid) {
        return Clientdata.stream("clientuuid", Sort.ascending("contactperson"), clientuuid).map(p -> (Clientdata) p).toList();
    }
/*
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

 */


}