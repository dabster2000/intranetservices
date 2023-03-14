package dk.trustworks.intranet.sales.services;

import dk.trustworks.intranet.contracts.model.enums.SalesStatus;
import dk.trustworks.intranet.sales.model.SalesLead;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.Arrays;
import java.util.List;

@JBossLog
@ApplicationScoped
public class SalesService {

    public SalesLead findOne(String uuid) {
        return SalesLead.findById(uuid);
    }

    public List<SalesLead> findAll() {
        return SalesLead.findAll().list();
    }

    public List<SalesLead> findByStatus(SalesStatus... status) {
        return SalesLead.find("IN ?1", Arrays.stream(status).toList()).list();
    }

    @Transactional
    public void persist(SalesLead salesLead) {
        salesLead.persist();
    }
}
