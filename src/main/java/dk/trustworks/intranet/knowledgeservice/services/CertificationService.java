package dk.trustworks.intranet.knowledgeservice.services;

import dk.trustworks.intranet.knowledgeservice.model.Certification;
import dk.trustworks.intranet.knowledgeservice.model.UserCertification;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.List;

@JBossLog
@ApplicationScoped
public class CertificationService {

    @Inject
    EntityManager em;

    public List<Certification> findAll() {
        return Certification.findAll().list();
    }

    public List<UserCertification> findAllUserCertifications() {
        return addCertficationNames(UserCertification.findAll().list());
    }

    public List<UserCertification> findAllUserCertifications(String useruuid) {
        return addCertficationNames(UserCertification.find("useruuid", useruuid).list());
    }

    public List<Certification> findAllCertificationsByUseruuid(String useruuid) {
        String sql = "select c.uuid, c.name from certifications c right join user_certifications uc on c.uuid = uc.certificationuuid where uc.useruuid like '"+useruuid+"'";
        return (List<Certification>) em.createNativeQuery(sql, Certification.class).getResultList();
    }

    private List<UserCertification> addCertficationNames(List<UserCertification> userCertificationList) {
        List<Certification> certifications = findAll();
        userCertificationList.forEach(uc -> certifications
                .stream().filter(certification -> certification.getUuid().equals(uc.getCertificationuuid())).findAny()
                .ifPresent(certification -> uc.setCertification(certification.getName())));
        return userCertificationList;
    }

}
