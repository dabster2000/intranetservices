package dk.trustworks.intranet.recruitmentservice.domain.ai.schemas;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Loads versioned JSON schemas from the classpath.
 *
 * <p>Schemas are stored under {@code META-INF/recruitment/schemas/{version}.json}
 * and are loaded once per JVM, then cached. Versioning is part of the file name
 * so schema revisions are immutable artifacts.
 */
@ApplicationScoped
public class SchemaCatalog {

    private static final String PATH_PREFIX = "META-INF/recruitment/schemas/";
    private static final String PATH_SUFFIX = ".json";

    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    public String load(String version) {
        return cache.computeIfAbsent(version, this::readFromClasspath);
    }

    private String readFromClasspath(String version) {
        String path = PATH_PREFIX + version + PATH_SUFFIX;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("schema not found: " + version);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read schema " + version, e);
        }
    }
}
