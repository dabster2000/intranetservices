package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dto.BannerNews;
import dk.trustworks.intranet.dto.RegularNews;
import dk.trustworks.intranet.newsservice.model.News;
import io.micrometer.core.annotation.Timed;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import java.util.List;

@JBossLog
@Tag(name = "News")
@Path("/news")
@RequestScoped
@RolesAllowed({"SYSTEM", "USER"})
@SecurityRequirement(name = "jwt")
@Timed(histogram = true)
public class NewsResource {

    @Inject
    dk.trustworks.intranet.newsservice.resources.NewsResource newsAPI;

    @GET
    public List<News> all() {
        return newsAPI.all();
    }
/*
    @GET
    @Path("/banners")
    public List<BannerNews> getAllBannerNews() {
        return newsAPI.getAllBannerNews();
    }

    @GET
    @Path("/banners/active")
    public List<BannerNews> getActiveBannerNews() {
        return newsAPI.getActiveBannerNews();
    }

    @GET
    @Path("/birthday")
    public List<BirthdayNews> getAllBirthdayNews() {
        return newsAPI.getAllBirthdayNews();
    }

    @GET
    @Path("/birthday/active")
    public List<BirthdayNews> getActiveBirthdayNews() {
        return newsAPI.getActiveBirthdayNews();
    }

    @GET
    @Path("/feed")
    public List<RegularNews> getAllRegularNews() {
        return newsAPI.getAllRegularNews();
    }

    @GET
    @Path("/feed/active")
    public List<RegularNews> getActiveRegularNews() {
        return newsAPI.getActiveRegularNews();
    }

 */
    @POST
    @Path("/banner")
    @Transactional
    public Response add(BannerNews news) {
        newsAPI.add(news);
        return Response.ok().build();
    }

    @POST
    @Path("/feed")
    @Transactional
    public Response add(RegularNews news) {
        newsAPI.add(news);
        return Response.ok().build();
    }
}
