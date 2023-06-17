    package dk.trustworks.intranet.knowledgeservice.services;

    import dk.trustworks.intranet.knowledgeservice.model.CkoCourse;
    import lombok.extern.jbosslog.JBossLog;

    import javax.enterprise.context.ApplicationScoped;
    import javax.transaction.Transactional;
    import java.time.LocalDate;
    import java.util.List;
    import java.util.UUID;

    /**
 * Created by hans on 27/06/2017.
 */


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
        System.out.println("Update course = " + course);
        CkoCourse.update("name = ?1, description = ?2, active = ?3 WHERE uuid like ?4 ", course.getName(), course.getDescription(), course.isActive(), course.getUuid());
    }

}
