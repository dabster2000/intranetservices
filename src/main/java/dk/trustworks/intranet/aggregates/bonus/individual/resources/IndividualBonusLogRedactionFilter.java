package dk.trustworks.intranet.aggregates.bonus.individual.resources;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;

/** Keep the actor in RequestHeaderHolder/audit while removing it from ordinary MDC output. */
@Provider
@Priority(Priorities.USER + 100)
public class IndividualBonusLogRedactionFilter implements ContainerRequestFilter, ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext context) {
        if (isIndividualBonus(context)) {
            MDC.remove("userUuid");
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (isIndividualBonus(request)) {
            response.getHeaders().putSingle("Cache-Control", "no-store");
        }
    }

    private static boolean isIndividualBonus(ContainerRequestContext context) {
        String path = context.getUriInfo().getPath();
        return path.startsWith("individual-bonuses") || path.startsWith("/individual-bonuses");
    }
}
