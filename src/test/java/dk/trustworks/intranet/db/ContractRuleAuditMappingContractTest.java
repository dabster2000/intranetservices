package dk.trustworks.intranet.db;

import dk.trustworks.intranet.contracts.model.ContractRuleAudit;
import jakarta.persistence.Column;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests binding the {@link ContractRuleAudit} mapping and its queries to the
 * {@code contract_rule_audit} DDL in V106.
 *
 * <p>Why this exists: the entity previously mapped seven columns that no migration ever
 * created ({@code table_name}, {@code entity_id}, {@code operation}, {@code old_values},
 * {@code new_values}, {@code modified_by}, {@code modified_at}), and every query in
 * {@code ContractRuleAuditService} filtered on {@code entityId} and ordered by a
 * {@code timestamp} property the entity did not declare. Neither fault could surface at
 * compile time — Panache query strings are parsed when they run, and the only reachable
 * caller sits behind a feature flag that is off — so both survived in the tree.
 *
 * <p>These checks need no database, so they run in the DB-free tier the CI deploy gate uses.
 */
class ContractRuleAuditMappingContractTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path SOURCES = Path.of("src/main/java/dk/trustworks/intranet/contracts");

    /** Words that may legitimately appear in an HQL fragment without being a property. */
    private static final Set<String> HQL_KEYWORDS = Set.of(
            "and", "or", "not", "order", "by", "asc", "desc", "is", "null", "in",
            "like", "between", "select", "from", "where", "count", "distinct", "as");

    @Test
    void entity_maps_exactly_the_columns_v106_creates() throws IOException {
        Set<String> ddlColumns = columnsOfCreateTable(
                Files.readString(MIGRATIONS.resolve("V106__Create_contract_override_tables.sql")),
                "contract_rule_audit");
        Set<String> mappedColumns = mappedColumnsOf(ContractRuleAudit.class);

        assertEquals(new TreeSet<>(ddlColumns), new TreeSet<>(mappedColumns),
                "ContractRuleAudit must map exactly the columns contract_rule_audit has. "
                        + "Columns only in the DDL are unmapped; columns only in the entity do not "
                        + "exist and every query touching them fails at runtime.");
    }

    @Test
    void v106_remains_the_only_migration_shaping_the_table() throws IOException {
        try (var files = Files.list(MIGRATIONS)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".sql") || name.startsWith("V106__")) continue;

                String sql = stripComments(Files.readString(file));
                assertTrue(!Pattern.compile("ALTER\\s+TABLE\\s+`?contract_rule_audit`?",
                                Pattern.CASE_INSENSITIVE).matcher(sql).find(),
                        name + " alters contract_rule_audit; update ContractRuleAudit and this test "
                                + "so the mapping still matches the table.");
            }
        }
    }

    @Test
    void every_query_property_exists_on_the_entity() throws IOException {
        Set<String> fields = declaredPropertyNames(ContractRuleAudit.class);

        for (String file : new String[]{
                "model/ContractRuleAudit.java", "services/ContractRuleAuditService.java"}) {
            String source = Files.readString(SOURCES.resolve(file));
            Matcher query = Pattern.compile("\\b(?:find|count)\\s*\\(\\s*\"([^\"]*)\"")
                    .matcher(source);

            while (query.find()) {
                String hql = query.group(1);
                Matcher identifier = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(hql);
                while (identifier.find()) {
                    String token = identifier.group();
                    if (HQL_KEYWORDS.contains(token.toLowerCase(Locale.ROOT))) continue;
                    assertTrue(fields.contains(token),
                            file + " queries property '" + token + "' which ContractRuleAudit does "
                                    + "not declare (in: \"" + hql + "\"). Declared: " + new TreeSet<>(fields));
                }
            }
        }
    }

    // --- helpers ---

    private static Set<String> columnsOfCreateTable(String sql, String table) {
        Matcher create = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?" + table + "`?\\s*\\(",
                Pattern.CASE_INSENSITIVE).matcher(stripComments(sql));
        assertTrue(create.find(), "no CREATE TABLE " + table + " found");

        String body = balancedBody(stripComments(sql), create.end() - 1);
        Set<String> columns = new LinkedHashSet<>();
        for (String part : splitTopLevel(body)) {
            if (Pattern.compile("^(PRIMARY\\s+KEY|UNIQUE|KEY|INDEX|CONSTRAINT|FOREIGN\\s+KEY|CHECK)\\b",
                    Pattern.CASE_INSENSITIVE).matcher(part).find()) continue;
            Matcher column = Pattern.compile("^`?([A-Za-z_][A-Za-z0-9_]*)`?\\s+\\S").matcher(part);
            if (column.find()) columns.add(column.group(1).toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private static Set<String> mappedColumnsOf(Class<?> entity) {
        Set<String> columns = new LinkedHashSet<>();
        for (Field field : persistentFields(entity)) {
            Column column = field.getAnnotation(Column.class);
            columns.add(column != null && !column.name().isBlank()
                    ? column.name().toLowerCase(Locale.ROOT)
                    : camelToSnake(field.getName()));
        }
        return columns;
    }

    private static Set<String> declaredPropertyNames(Class<?> entity) {
        Set<String> names = new LinkedHashSet<>();
        for (Field field : persistentFields(entity)) names.add(field.getName());
        return names;
    }

    private static java.util.List<Field> persistentFields(Class<?> entity) {
        return Arrays.stream(entity.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> !f.isSynthetic())
                .filter(f -> !f.getName().contains("$"))
                .filter(f -> f.getAnnotation(Transient.class) == null)
                .toList();
    }

    private static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.replaceAll("(?s)/\\*.*?\\*/", " ").split("\n", -1)) {
            Matcher comment = Pattern.compile("--(\\s|$)").matcher(line);
            out.append(comment.find() ? line.substring(0, comment.start()) : line).append('\n');
        }
        return out.toString();
    }

    private static String balancedBody(String sql, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return sql.substring(openIndex + 1, i);
        }
        throw new IllegalStateException("unbalanced CREATE TABLE body");
    }

    private static java.util.List<String> splitTopLevel(String body) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        Character quote = null;
        for (char c : body.toCharArray()) {
            if (quote != null) {
                current.append(c);
                if (c == quote) quote = null;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') { quote = c; current.append(c); continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (c == ',' && depth == 0) { parts.add(current.toString().trim()); current.setLength(0); }
            else current.append(c);
        }
        parts.add(current.toString().trim());
        return parts.stream().filter(p -> !p.isBlank()).toList();
    }
}
