package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.Clientdata;
import io.quarkus.panache.common.Sort;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.ws.rs.PathParam;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ClientDataService {

    public List<Clientdata> listAll() {
        return Clientdata.streamAll(Sort.ascending("clientname")).map(p -> (Clientdata) p).collect(Collectors.toList());
    }

    public Clientdata findByUuid(@PathParam("uuid") String uuid) {
        return Clientdata.findById(uuid);
    }

    @Transactional
    public Clientdata save(Clientdata clientdata) {
        if(clientdata.getUuid()!=null || clientdata.getUuid().isEmpty()) clientdata.setUuid(UUID.randomUUID().toString());
        clientdata.persist();
        return clientdata;
    }

    @Transactional
    public void updateOne(Clientdata clientdata) {
        Clientdata.update("city = ?1, " +
                        "clientname = ?2, " +
                        "contactperson = ?3, " +
                        "cvr = ?4, " +
                        "ean = ?5, " +
                        "otheraddressinfo = ?6, " +
                        "postalcode = ?7, " +
                        "streetnamenumber = ?8 " +
                        "WHERE uuid like ?9 ",
                clientdata.getCity(),
                clientdata.getClientname(),
                clientdata.getContactperson(),
                clientdata.getCvr(),
                clientdata.getEan(),
                clientdata.getOtheraddressinfo(),
                clientdata.getPostalcode(),
                clientdata.getStreetnamenumber(),
                clientdata.getUuid());
    }

    @Transactional
    public void delete(@PathParam("uuid") String uuid) {
        Clientdata.deleteById(uuid);
    }
}