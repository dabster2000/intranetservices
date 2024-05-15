package dk.trustworks.intranet.sales.services;

import dk.trustworks.intranet.contracts.model.enums.SalesStatus;
import dk.trustworks.intranet.sales.model.SalesLead;
import dk.trustworks.intranet.sales.model.SalesLeadConsultant;
import dk.trustworks.intranet.userservice.model.User;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
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
        if(salesLead.getUuid()==null || salesLead.getUuid().isBlank()) {
            salesLead.setUuid(UUID.randomUUID().toString());
            salesLead.setCreated(LocalDateTime.now());
            salesLead.persist();
        } else if(SalesLead.findById(salesLead.getUuid())==null) salesLead.persist();
        else update(salesLead);
    }

    @Transactional
    public void addConsultant(String salesLeaduuid, User user) {
        new SalesLeadConsultant(SalesLead.findById(salesLeaduuid), user).persist();
    }

    @Transactional
    public void removeConsultant(String salesleaduuid, String useruuid) {
        SalesLead salesLead = SalesLead.findById(salesleaduuid);
        User user = User.findById(useruuid);
        SalesLeadConsultant.delete("lead = ?1 and user = ?2", salesLead, user);
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
                        "extension = ?7, " +
                        "rate = ?8, " +
                        "period = ?9, " +
                        "contactInformation = ?10, " +
                        "description = ?11, " +
                        "status = ?12 " +
                        "WHERE uuid like ?13 ",
                salesLead.getClient(),
                salesLead.getAllocation(),
                salesLead.getCloseDate(),
                salesLead.getCompetencies(),
                salesLead.getLeadManager(),
                salesLead.getConsultantLevel(),
                salesLead.isExtension(),
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
