package dk.trustworks.intranet.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders the machine-readable permission catalogue artifact consumed by the
 * frontend (authorization-model-unification Phase 5, task 5.2).
 *
 * <p>The frontend cannot fetch the catalogue from a running environment in CI
 * (no catalogue-listing endpoint exists, and staging may not carry this phase
 * yet), so it vendors a copy of {@code docs/access/permission-catalogue.json}
 * and generates its {@code Permission} string-literal union from it. This file
 * is therefore a cross-repo contract: the committed artifact must be
 * byte-identical to {@link #render()}; {@code PermissionCatalogueArtifactTest}
 * asserts this in the fast test tier, which gates every deploy (Phase 2). To
 * regenerate after editing {@link Permissions}:
 *
 * <pre>
 *   ./mvnw -q compile
 *   java -cp target/classes dk.trustworks.intranet.security.PermissionCatalogueJson
 * </pre>
 *
 * <p>Rendering is by hand rather than via Jackson so the byte layout is
 * deterministic and dependency-free: keys sorted, two-space indent, UTF-8,
 * trailing newline.
 */
public final class PermissionCatalogueJson {

    static final String ARTIFACT_FILE = "docs/access/permission-catalogue.json";

    public static String render() {
        List<Permissions.Permission> sorted = new ArrayList<>(Permissions.CATALOGUE);
        sorted.sort(Comparator.comparing(Permissions.Permission::key));
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"generatedFrom\": \"dk.trustworks.intranet.security.Permissions\",\n");
        sb.append("  \"generator\": \"dk.trustworks.intranet.security.PermissionCatalogueJson\",\n");
        sb.append("  \"count\": ").append(sorted.size()).append(",\n");
        sb.append("  \"permissions\": [\n");
        for (int i = 0; i < sorted.size(); i++) {
            Permissions.Permission p = sorted.get(i);
            sb.append("    { \"key\": ").append(jsonString(p.key()))
              .append(", \"displayName\": ").append(jsonString(p.displayName()))
              .append(", \"category\": ").append(jsonString(p.category()))
              .append(" }").append(i < sorted.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : ARTIFACT_FILE);
        Files.writeString(target, render(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + target + " (" + Permissions.CATALOGUE.size() + " permissions)");
    }

    private PermissionCatalogueJson() {
    }
}
