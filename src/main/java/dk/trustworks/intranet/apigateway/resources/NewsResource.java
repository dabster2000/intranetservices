package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.newsservice.model.News;
import dk.trustworks.intranet.newsservice.resources.NewsService;
import io.micrometer.core.annotation.Timed;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.util.List;

@JBossLog
@Tag(name = "News")
@Path("/news")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
@Timed(histogram = true)
public class NewsResource {

    @Inject
    NewsService newsAPI;
    @GET
    public List<News> all() {
        return newsAPI.findAll();
    }

    @GET
    @Path("/active")
    public List<News> getActiveNews(@QueryParam("type") String newsType) {
        return newsAPI.getActiveNews(newsType);
    }

    @POST
    @Transactional
    public Response save(News news) {
        newsAPI.save(news);
        return Response.ok().build();
    }

    @DELETE
    @Path("/{newsuuid}")
    @Transactional
    public Response delete(@PathParam("newsuuid") String uuid) {
        newsAPI.delete(uuid);
        return Response.ok().build();
    }
}
