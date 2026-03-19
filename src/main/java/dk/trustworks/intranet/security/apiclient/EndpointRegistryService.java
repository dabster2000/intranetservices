package dk.trustworks.intranet.security.apiclient;

import dk.trustworks.intranet.security.apiclient.dto.EndpointRegistryEntry;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Scans all JAX-RS resource classes at startup and caches
 * a registry of endpoints with their security annotations.
 * The result is immutable and served from memory on every request.
 */
@ApplicationScoped
@JBossLog
public class EndpointRegistryService {

    private static final Map<String, String> DOMAIN_MAP = Map.ofEntries(
            Map.entry("crm", "CRM"),
            Map.entry("invoice", "Invoicing"),
            Map.entry("bonus", "Invoicing"),
            Map.entry("users", "Users"),
            Map.entry("userservice", "Users"),
            Map.entry("client", "Clients"),
            Map.entry("finance", "Finance"),
            Map.entry("jkdashboard", "Finance"),
            Map.entry("revenue", "Revenue"),
            Map.entry("contracts", "Contracts"),
            Map.entry("knowledge", "Knowledge"),
            Map.entry("cvtool", "Knowledge"),
            Map.entry("conference", "Knowledge"),
            Map.entry("work", "Time Registration"),
            Map.entry("capacity", "Time Registration"),
            Map.entry("availability", "Time Registration"),
            Map.entry("delivery", "Time Registration"),
            Map.entry("expense", "Expenses"),
            Map.entry("expenseservice", "Expenses"),
            Map.entry("utilization", "Utilization"),
            Map.entry("signing", "E-Signature"),
            Map.entry("security", "Admin/Auth"),
            Map.entry("apiclient", "Admin/Auth"),
            Map.entry("apigateway", "Admin/Auth"),
            Map.entry("config", "Admin/Auth"),
            Map.entry("logging", "Admin/Auth"),
            Map.entry("public", "Public"),
            Map.entry("gate", "Public"),
            Map.entry("consultant", "Consulting"),
            Map.entry("budgets", "Budgets"),
            Map.entry("accounting", "Accounting"),
            Map.entry("snapshot", "Snapshots"),
            Map.entry("cultureservice", "Culture"),
            Map.entry("lunch", "Culture"),
            Map.entry("documentservice", "Documents"),
            Map.entry("fileservice", "Documents"),
            Map.entry("sharepoint", "Documents"),
            Map.entry("openai", "AI"),
            Map.entry("forms", "Forms")
    );

    private static final Set<Class<?>> HTTP_METHOD_ANNOTATIONS = Set.of(
            GET.class, POST.class, PUT.class, DELETE.class, PATCH.class
    );

    @Inject
    BeanManager beanManager;

    private List<EndpointRegistryEntry> cachedEntries = List.of();

    void onStart(@Observes StartupEvent ev) {
        cachedEntries = scanEndpoints();
        log.infof("Endpoint registry initialized: %d endpoints discovered", cachedEntries.size());
    }

    /**
     * Returns the cached, immutable list of all discovered endpoints.
     */
    public List<EndpointRegistryEntry> getEndpoints() {
        return cachedEntries;
    }

    private List<EndpointRegistryEntry> scanEndpoints() {
        var entries = new ArrayList<EndpointRegistryEntry>();
        var processedClasses = new HashSet<Class<?>>();

        for (Bean<?> bean : beanManager.getBeans(Object.class)) {
            Class<?> beanClass = bean.getBeanClass();

            // Deduplicate: skip if we've already processed this class (CDI proxies)
            if (!processedClasses.add(beanClass)) {
                continue;
            }

            Path classPath = beanClass.getAnnotation(Path.class);
            if (classPath == null) {
                continue;
            }

            String basePath = normalizePath(classPath.value());
            String[] classRoles = getClassRoles(beanClass);
            boolean classPermitAll = beanClass.isAnnotationPresent(PermitAll.class);
            String domain = deriveDomain(beanClass);

            for (Method method : beanClass.getDeclaredMethods()) {
                String httpMethod = resolveHttpMethod(method);
                if (httpMethod == null) {
                    continue;
                }

                Path methodPath = method.getAnnotation(Path.class);
                String fullPath = methodPath != null
                        ? basePath + normalizePath(methodPath.value())
                        : basePath;

                // Method-level annotations override class-level
                boolean methodPermitAll = method.isAnnotationPresent(PermitAll.class);
                RolesAllowed methodRoles = method.getAnnotation(RolesAllowed.class);

                boolean effectivePermitAll;
                List<String> effectiveRoles;

                if (methodPermitAll) {
                    effectivePermitAll = true;
                    effectiveRoles = List.of();
                } else if (methodRoles != null) {
                    effectivePermitAll = false;
                    effectiveRoles = List.of(methodRoles.value());
                } else if (classPermitAll) {
                    effectivePermitAll = true;
                    effectiveRoles = List.of();
                } else {
                    effectivePermitAll = false;
                    effectiveRoles = classRoles != null ? List.of(classRoles) : List.of();
                }

                entries.add(new EndpointRegistryEntry(
                        httpMethod, fullPath, effectiveRoles, effectivePermitAll, domain));
            }
        }

        // Sort by path then method for stable output
        entries.sort(Comparator.comparing(EndpointRegistryEntry::path)
                .thenComparing(EndpointRegistryEntry::method));

        return Collections.unmodifiableList(entries);
    }

    private String resolveHttpMethod(Method method) {
        if (method.isAnnotationPresent(GET.class)) return "GET";
        if (method.isAnnotationPresent(POST.class)) return "POST";
        if (method.isAnnotationPresent(PUT.class)) return "PUT";
        if (method.isAnnotationPresent(DELETE.class)) return "DELETE";
        if (method.isAnnotationPresent(PATCH.class)) return "PATCH";
        return null;
    }

    private String[] getClassRoles(Class<?> clazz) {
        RolesAllowed annotation = clazz.getAnnotation(RolesAllowed.class);
        return annotation != null ? annotation.value() : null;
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "";
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Derives a human-readable domain name from the resource class's package.
     * Checks each segment of the package name against the known domain map.
     */
    String deriveDomain(Class<?> resourceClass) {
        String packageName = resourceClass.getPackageName().toLowerCase();
        String[] segments = packageName.split("\\.");

        // Check segments in reverse order (most specific first)
        for (int i = segments.length - 1; i >= 0; i--) {
            String mapped = DOMAIN_MAP.get(segments[i]);
            if (mapped != null) {
                return mapped;
            }
        }
        return "Other";
    }
}
