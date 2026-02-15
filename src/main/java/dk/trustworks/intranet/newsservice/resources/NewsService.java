package dk.trustworks.intranet.newsservice.resources;

import dk.trustworks.intranet.newsservice.model.News;
import dk.trustworks.intranet.newsservice.model.RelatedResource;
import dk.trustworks.intranet.newsservice.model.enums.NewsType;
import dk.trustworks.intranet.userservice.model.Employee;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class NewsService {

    @Inject
    EntityManager em;

    public List<News> findAll() {
        return News.listAll();
    }

    public List<News> getActiveNews(@QueryParam("category") String newsCategory) {
        // Add null/empty check at the start for safety
        if(newsCategory == null || newsCategory.isBlank()) {
            return new ArrayList<>();  // Return empty list for safety
        }

        // Use safe null comparison pattern
        if("banner".equals(newsCategory)) {
            return News.find("?1 between startDate and endDate AND newsType = ?2", LocalDateTime.now(), NewsType.BANNER).list();
            //5f6fac9d-f52d-462f-ab27-be7eeef1b3f3
        } else if("events".equals(newsCategory)) {
            List<News> newsList = News.find("eventDate >= ?1 and eventDate <= ?2 and newsType IN ('BIRTHDAY', 'INFO', 'NEW_EMPLOYEE', 'INTERNAL_EVENT', 'INTERNAL_COURSE', 'EXTERNAL_EVENT', 'CONFERENCE', 'HQ_BOOKING', 'CLIENT_EVENT', 'HQ') ", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusMonths(6)).list();
            List<Employee> employees = em.createNativeQuery("select * from consultant where status like 'ACTIVE' and consultanttype not like 'EXTERNAL' and CURDATE() between DATE_SUB(DATE_FORMAT(DATE_ADD(birthday, INTERVAL (YEAR(CURRENT_DATE()) - YEAR(birthday)) YEAR), '%Y-%m-%d'), INTERVAL 1 MONTH ) and DATE_ADD(DATE_FORMAT(DATE_ADD(birthday, INTERVAL (YEAR(CURRENT_DATE()) - YEAR(birthday)) YEAR), '%Y-%m-%d'), INTERVAL 7 DAY)", Employee.class).getResultList();
            for (Employee employee : employees) {
                LocalDate birthdayThisYear = employee.getBirthday().withYear(LocalDate.now().getYear());
                LocalDateTime eventDate = birthdayThisYear.isBefore(LocalDate.now())
                        ? birthdayThisYear.plusYears(1).atStartOfDay()
                        : birthdayThisYear.atStartOfDay();
                newsList.add(new News(eventDate, NewsType.BIRTHDAY, employee.uuid, employee.getFirstname() + " " + employee.getLastname() + "'s Birthday"));
            }
            return newsList;
        } else if ("office_display".equals(newsCategory)) {
            return News.find("eventDate >= ?1 and eventDate <= ?2 and newsType IN ('NEW_EMPLOYEE', 'INTERNAL_EVENT', 'INTERNAL_COURSE', 'EXTERNAL_EVENT', 'CONFERENCE', 'CLIENT_EVENT', 'INFO', 'HQ','HQ_BOOKING') ", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusMonths(6)).list();
        } else if ("mobile_app".equals(newsCategory)) {
            return News.find("eventDate >= ?1 and eventDate <= ?2 and newsType IN ('NEW_EMPLOYEE', 'INTERNAL_EVENT', 'INTERNAL_COURSE', 'EXTERNAL_EVENT', 'CONFERENCE', 'CLIENT_EVENT', 'INFO', 'HQ') ", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusMonths(6)).list();
        }
        return new ArrayList<>();
    }

    @Transactional
    public void save(News news) {
        if(news.getUuid() == null || news.getUuid().isBlank()) {
            news.setUuid(UUID.randomUUID().toString());
            persist(news);
        } else {
            if(News.findByIdOptional(news.getUuid()).isPresent()) {
                // update existing
                News.update("startDate = ?1, endDate = ?2, eventDate = ?3, text = ?4, description = ?5 WHERE uuid like ?6 ",
                        news.getStartDate(),
                        news.getEndDate(),
                        news.getEventDate(),
                        news.getText(),
                        news.getDescription(),
                        news.getUuid());
            } else {
                persist(news);
            }
        }
    }

    private void persist(News news) {
        for (RelatedResource relatedResource : news.getRelatedResources()) {
            if(relatedResource.getUuid() == null || news.getUuid().isBlank()) relatedResource.setUuid(UUID.randomUUID().toString());
        }
        news.persist();
    }

    @Transactional
    public void delete(@PathParam("newsuuid") String uuid) {
        News.deleteById(uuid);
    }
}
