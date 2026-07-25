package dk.trustworks.intranet.security;

import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI filter that removes all paths except those under {@code /public}.
 *
 * <p>Referenced from {@code application.yml} via {@code mp.openapi.filter}. It was previously
 * wired up as {@code quarkus.smallrye-openapi.filter}, which Quarkus reports as an unrecognized
 * key and ignores — so the filter never ran and the spec published the entire API surface.
 *
 * <p>Currently inert either way: {@code quarkus.smallrye-openapi.enabled} is {@code false}, so no
 * document is served. This matters again the moment someone turns the spec back on.
 */
public class PublicOnlyOASFilter implements OASFilter {

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (openAPI.getPaths() == null) return;

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().getPathItems().entrySet()) {
            // /login is deliberately absent: the legacy credentials-in-query-string endpoint is
            // disabled and must not be advertised if the spec is ever re-enabled.
            if (!entry.getKey().startsWith("/public") && !entry.getKey().equals("/auth/token")) {
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(path -> openAPI.getPaths().removePathItem(path));
    }
}
