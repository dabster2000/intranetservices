package dk.trustworks.intranet.knowledgeservice.services;

import dk.trustworks.intranet.knowledgeservice.model.CkoCourse;
import dk.trustworks.intranet.knowledgeservice.model.CkoCourseParticipant;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;


@JBossLog
@ApplicationScoped
public class CourseService {

    public List<CkoCourse> findAll() {
        return CkoCourse.listAll();
    }

    public List<CkoCourse> findAllByCourseTypeAndActiveTrue(String type) {
        return CkoCourse.list("courseType like ?1 AND active = ?2", type, true);
    }

    @Transactional
    public void create(CkoCourse course) {
        if (course.getUuid() == null || CkoCourse.findByIdOptional(course.getUuid()).isEmpty()) {
            System.out.println("Persist course = " + course);
            course.setUuid(UUID.randomUUID().toString());
            course.setActive(true);
            course.setCreated(LocalDate.now());
            course.persist();
        } else {
            update(course);
        }
    }

    @Transactional
    public void update(CkoCourse course) {
        CkoCourse.update("name = ?1, description = ?2, active = ?3 WHERE uuid like ?4 ", course.getName(), course.getDescription(), course.isActive(), course.getUuid());
    }

    public List<CkoCourseParticipant> findAllSignedUpUsers(CkoCourse ckoCourse) {
        return CkoCourseParticipant.find("ckoCourse = ?1 AND status = ?2", ckoCourse, "SIGNED_UP").list();
    }

    public List<CkoCourseParticipant> findAllParticipantsByUser(User user) {
        return CkoCourseParticipant.find("user", user).list();
    }

    @Transactional
    public void addParticipants(CkoCourse course, User user) {
        CkoCourseParticipant participant = new CkoCourseParticipant(UUID.randomUUID().toString(), course, user, "SIGNED_UP", LocalDate.now());
        participant.persist();
    }

    @Transactional
    public void removeParticipant(String useruuid) {
        CkoCourseParticipant.delete("user.uuid like ?1", useruuid);
    }
}
