package dk.trustworks.intranet.knowledgeservice.services;

import dk.trustworks.intranet.knowledgeservice.model.CKOExpense;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class CkoExpenseService {

    public List<CKOExpense> findAll() {
        return CKOExpense.findAll().list();
    }

    public List<CKOExpense> findExpensesByUseruuid(String useruuid) {
        return CKOExpense.find("useruuid like ?1", useruuid).list();
    }

    public List<CKOExpense> findByDescription(String description) {
        return CKOExpense.find("description like ?1", new String(Base64.getDecoder().decode(description))).list();
    }

    @Transactional
    public void saveExpense(CKOExpense ckoExpense) {
        if(ckoExpense.getUuid()==null || ckoExpense.getUuid().isEmpty()) {
            ckoExpense.setUuid(UUID.randomUUID().toString());
        } else {
            if(CKOExpense.findByIdOptional(ckoExpense.getUuid()).isPresent()) {
                updateExpense(ckoExpense);
                return;
            }
        }
        CKOExpense.persist(ckoExpense);
    }

    @Transactional
    public void updateExpense(CKOExpense ckoExpense) {
        CKOExpense.update("eventdate = ?1, " +
                "description = ?2, " +
                "price = ?3, " +
                "comment = ?4, " +
                "days = ?5, " +
                "type = ?6, " +
                "status = ?7, " +
                "purpose = ?8, " +
                "rating = ?9, " +
                "rating_comment = ?10, " +
                "certification = ?11, " +
                "certified = ?12 " +
                "where uuid like ?13 ",
                ckoExpense.getEventdate(),
                ckoExpense.getDescription(),
                ckoExpense.getPrice(),
                ckoExpense.getComment(),
                ckoExpense.getDays(),
                ckoExpense.getType(),
                ckoExpense.getStatus(),
                ckoExpense.getPurpose(),
                ckoExpense.getRating(),
                ckoExpense.getRating_comment(),
                ckoExpense.getCertification(),
                ckoExpense.getCertified(),
                ckoExpense.getUuid());
    }

    @Transactional
    public void deleteExpense(String uuid) {
        CKOExpense.deleteById(uuid);
    }
}
