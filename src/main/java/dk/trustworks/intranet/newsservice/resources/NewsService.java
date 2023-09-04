package dk.trustworks.intranet.newsservice.resources;

import dk.trustworks.intranet.newsservice.model.News;
import dk.trustworks.intranet.newsservice.model.RelatedResource;
import dk.trustworks.intranet.userservice.model.Consultant;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import javax.ws.rs.*;
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

    @GET
    public List<News> findAll() {
        return News.listAll();
    }

    @GET
    @Path("/active")
    public List<News> getActiveNews(@QueryParam("type") String newsType) {
        if(newsType.equals("banner")) {
            return News.find("?1 between startdate and enddate AND newsType like ?2", LocalDateTime.now(), newsType).list();
            //5f6fac9d-f52d-462f-ab27-be7eeef1b3f3
        } else if(newsType.equals("events")) {
            List<News> newsList = News.find("?1 <= eventdate and newsType like ?2", LocalDateTime.now().plusMonths(1), newsType).list();
            List<Consultant> consultants = em.createNativeQuery("select * from consultant where status like 'ACTIVE' and CURDATE() between DATE_SUB(DATE_FORMAT(DATE_ADD(birthday, INTERVAL (YEAR(CURRENT_DATE()) - YEAR(birthday)) YEAR), '%Y-%m-%d'), INTERVAL 1 MONTH ) and DATE_ADD(DATE_FORMAT(DATE_ADD(birthday, INTERVAL (YEAR(CURRENT_DATE()) - YEAR(birthday)) YEAR), '%Y-%m-%d'), INTERVAL 7 DAY)", Consultant.class).getResultList();
            for (Consultant consultant : consultants) {
                newsList.add(new News(consultant.getBirthday().withYear(LocalDate.now().getYear()).isBefore(LocalDate.now())?consultant.getBirthday().withYear(LocalDate.now().getYear()).plusYears(1).atStartOfDay():consultant.getBirthday().atStartOfDay(), "events", consultant.uuid, consultant.getFirstname() + " " + consultant.getLastname() + "'s Birthday"));
            }
            return newsList;
        }
        return new ArrayList<>();
    }

    @POST
    @Transactional
    public void save(News news) {
        if(news.getUuid() == null || news.getUuid().isBlank()) {
            news.setUuid(UUID.randomUUID().toString());
            persist(news);
        } else {
            if(News.findByIdOptional(news.getUuid()).isPresent()) {
                // update existing
                News.update("startdate = ?1, enddate = ?2, eventdate = ?3, text = ?4, description = ?5 WHERE uuid like ?6 ",
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

    @DELETE
    @Path("/{newsuuid}")
    @Transactional
    public void delete(@PathParam("newsuuid") String uuid) {
        News.deleteById(uuid);
    }
}
