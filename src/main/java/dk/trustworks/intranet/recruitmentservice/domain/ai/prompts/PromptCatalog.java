package dk.trustworks.intranet.recruitmentservice.domain.ai.prompts;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Loads versioned prompt templates from the classpath.
 *
 * <p>Prompts are stored under {@code META-INF/recruitment/prompts/{version}.txt}
 * and are loaded once per JVM, then cached. Versioning is part of the file name
 * so that prompt revisions are immutable artifacts: the previously-named version
 * is never mutated, only superseded by a new version.
 */
@ApplicationScoped
public class PromptCatalog {

    private static final String PATH_PREFIX = "META-INF/recruitment/prompts/";
    private static final String PATH_SUFFIX = ".txt";

    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    public String load(String version) {
        return cache.computeIfAbsent(version, this::readFromClasspath);
    }

    private String readFromClasspath(String version) {
        String path = PATH_PREFIX + version + PATH_SUFFIX;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("prompt not found: " + version);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read prompt " + version, e);
        }
    }
}
