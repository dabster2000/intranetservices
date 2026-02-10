package dk.trustworks.intranet.security;

import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI filter that removes all paths except those under {@code /public}.
 * Referenced from {@code application.yml} via {@code quarkus.smallrye-openapi.filter}.
 */
public class PublicOnlyOASFilter implements OASFilter {

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (openAPI.getPaths() == null) return;

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().getPathItems().entrySet()) {
            if (!entry.getKey().startsWith("/public") && !entry.getKey().startsWith("/login")) {
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(path -> openAPI.getPaths().removePathItem(path));
    }
}
