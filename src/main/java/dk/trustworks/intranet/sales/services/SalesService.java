package dk.trustworks.intranet.sales.services;

import dk.trustworks.intranet.contracts.model.enums.SalesStatus;
import dk.trustworks.intranet.sales.model.SalesLead;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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
        log.info("SalesService.persist");
        log.info("salesLead = " + salesLead);
        if(salesLead.getUuid()==null || salesLead.getUuid().isBlank()) {
            salesLead.setUuid(UUID.randomUUID().toString());
            salesLead.persist();
        } else if(SalesLead.findById(salesLead.getUuid())==null) salesLead.persist();
        else update(salesLead);
    }

    @Transactional
    public void update(SalesLead salesLead) {
        log.info("SalesService.update");
        log.info("salesLead = " + salesLead);
        SalesLead.update("client = ?1, " +
                        "allocation = ?2, " +
                        "closeDate = ?3, " +
                        "competencies = ?4, " +
                        "leadManager = ?5, " +
                        "consultantLevel = ?6, " +
                        "rate = ?7, " +
                        "period = ?8, " +
                        "contactInformation = ?9, " +
                        "description = ?10, " +
                        "status = ?11 " +
                        "WHERE uuid like ?12 ",
                salesLead.getClient(),
                salesLead.getAllocation(),
                salesLead.getCloseDate(),
                salesLead.getCompetencies(),
                salesLead.getLeadManager(),
                salesLead.getConsultantLevel(),
                salesLead.getRate(),
                salesLead.getPeriod(),
                salesLead.getContactInformation(),
                salesLead.getDescription(),
                salesLead.getStatus(),
                salesLead.getUuid());
    }

    @Transactional
    public void delete(String uuid) {
        SalesLead.deleteById(uuid);
    }
}
