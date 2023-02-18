package dk.trustworks.intranet.newsservice.resources;

import dk.trustworks.intranet.newsservice.model.News;
import dk.trustworks.intranet.newsservice.model.RelatedResource;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@JBossLog
@Path("/news")
@ApplicationScoped
public class NewsResource {

    @GET
    public List<News> all() {
        return News.listAll();
    }

    @GET
    @Path("/active")
    public List<News> getActiveNews(@QueryParam("type") String newsType) {
        return News.find("?1 between startdate and enddate AND newsType like ?2", LocalDateTime.now(), newsType).list();
    }

    @POST
    @Transactional
    public Response add(News news) {
        if(news.getUuid() == null || news.getUuid().isBlank()) news.setUuid(UUID.randomUUID().toString());
        for (RelatedResource relatedResource : news.getRelatedResources()) {
            if(relatedResource.getUuid() == null || news.getUuid().isBlank()) relatedResource.setUuid(UUID.randomUUID().toString());
        }

        news.persist();
        return Response.ok().build();
    }
}
