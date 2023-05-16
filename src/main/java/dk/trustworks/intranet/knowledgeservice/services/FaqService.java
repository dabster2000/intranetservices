package dk.trustworks.intranet.knowledgeservice.services;

import dk.trustworks.intranet.knowledgeservice.model.Faq;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.List;

/**
 * Created by hans on 27/06/2017.
 */


@JBossLog
@ApplicationScoped
public class FaqService {

    public List<Faq> findAll() {
        return Faq.listAll();
    }

    @Transactional
    public void create(Faq faq) {
        if(Faq.findByIdOptional(faq.getUuid()).isPresent()) update(faq);
        else faq.persist();
    }

    @Transactional
    public void update(Faq faq) {
        Faq.update("faqgroup = ?1, title = ?2, content = ?3 WHERE uuid like ?4 ", faq.getFaqgroup(), faq.getTitle(), faq.getContent(), faq.getUuid());
    }

    @Transactional
    public void delete(String uuid) {
        Faq.deleteById(uuid);
    }
}
