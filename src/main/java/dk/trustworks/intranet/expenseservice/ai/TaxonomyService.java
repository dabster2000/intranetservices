package dk.trustworks.intranet.expenseservice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@JBossLog
@ApplicationScoped
public class TaxonomyService {

    private static final String RESOURCE = "/data/taxonomy_trustworks.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Category> categoriesById = new HashMap<>();
    private final Map<String, Set<String>> synonymsIndex = new HashMap<>(); // normalized -> categoryIds
    private final Map<String, Rollup> rollupsById = new HashMap<>();

    @PostConstruct
    void init() {
        try (InputStream is = getClass().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                log.warnf("Taxonomy resource not found: %s", RESOURCE);
                return;
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(json);
            // Load categories
            JsonNode cats = root.path("categories");
            if (cats.isArray()) {
                for (JsonNode c : cats) {
                    String id = optText(c, "id");
                    if (id == null || id.isBlank()) continue;
                    Category cat = new Category();
                    cat.id = id;
                    cat.nameDa = optText(c, "name_da");
                    cat.nameEn = optText(c, "name_en");
                    cat.parent = optText(c, "parent");
                    cat.type = optText(c, "type");
                    cat.vatHint = optText(c, "vat_hint");
                    // synonyms
                    Set<String> syns = new HashSet<>();
                    JsonNode arr = c.path("synonyms");
                    if (arr.isArray()) {
                        for (JsonNode s : arr) syns.add(norm(s.asText()));
                    }
                    // also index names as synonyms
                    if (cat.nameDa != null) syns.add(norm(cat.nameDa));
                    if (cat.nameEn != null) syns.add(norm(cat.nameEn));
                    syns.add(norm(id));
                    cat.synonyms = syns;
                    categoriesById.put(id, cat);
                    for (String k : syns) synonymsIndex.computeIfAbsent(k, x -> new HashSet<>()).add(id);
                }
            }
            // Load rollups
            JsonNode rollups = root.path("rollups");
            if (rollups.isObject()) {
                rollups.fields().forEachRemaining(e -> {
                    String id = e.getKey();
                    JsonNode r = e.getValue();
                    Rollup roll = new Rollup();
                    roll.id = id;
                    roll.nameDa = optText(r, "name_da");
                    // includes
                    Set<String> includes = new HashSet<>();
                    JsonNode inc = r.path("includes");
                    if (inc.isArray()) {
                        for (JsonNode s : inc) includes.add(s.asText());
                    }
                    roll.includes = includes;
                    rollupsById.put(id, roll);
                });
            }
            log.infof("Loaded taxonomy: %d categories, %d rollups", categoriesById.size(), rollupsById.size());
        } catch (Exception e) {
            log.error("Failed to load taxonomy", e);
        }
    }

    public boolean isReady() {
        return !categoriesById.isEmpty();
    }

    public List<String> getAllCategoryIds() {
        return new ArrayList<>(categoriesById.keySet());
    }

    public Optional<Category> getCategory(String id) {
        return Optional.ofNullable(categoriesById.get(id));
    }

    public String normalizeCategory(String input) {
        if (input == null || input.isBlank()) return null;
        String key = norm(input);
        Set<String> ids = synonymsIndex.get(key);
        if (ids == null || ids.isEmpty()) return null;
        // Prefer leaf (type = spend) if multiple
        return ids.stream().sorted((a, b) -> {
            Category ca = categoriesById.get(a);
            Category cb = categoriesById.get(b);
            boolean aSpend = ca != null && "spend".equalsIgnoreCase(ca.type);
            boolean bSpend = cb != null && "spend".equalsIgnoreCase(cb.type);
            if (aSpend == bSpend) return a.compareTo(b);
            return aSpend ? -1 : 1;
        }).findFirst().orElse(ids.iterator().next());
    }

    public Set<String> expandParents(String categoryId) {
        Set<String> out = new LinkedHashSet<>();
        String cur = categoryId;
        int guard = 0;
        while (cur != null && guard++ < 20) {
            out.add(cur);
            Category c = categoriesById.get(cur);
            if (c == null || c.parent == null || c.parent.isBlank()) break;
            cur = c.parent;
        }
        return out;
    }

    public Set<String> expandToRollups(Collection<String> categoryIds) {
        Set<String> tags = new LinkedHashSet<>();
        if (categoryIds == null) return tags;
        tags.addAll(categoryIds);
        for (Map.Entry<String, Rollup> e : rollupsById.entrySet()) {
            Rollup r = e.getValue();
            if (intersects(r.includes, categoryIds)) tags.add(r.id);
        }
        return tags;
    }

    public List<String> getLimitedCategoryIds(int limit) {
        return categoriesById.keySet().stream().sorted().limit(limit).collect(Collectors.toList());
    }

    public Set<String> getRollupIncludes(String rollupId) {
        Rollup r = rollupsById.get(rollupId);
        return r != null && r.includes != null ? r.includes : Collections.emptySet();
    }

    private boolean intersects(Set<String> a, Collection<String> b) {
        for (String x : b) if (a.contains(x)) return true;
        return false;
    }

    private static String optText(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private static String norm(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    public static class Category {
        public String id;
        public String nameDa;
        public String nameEn;
        public String parent;
        public String type; // group/spend
        public String vatHint;
        public Set<String> synonyms = Set.of();
    }

    public static class Rollup {
        public String id;
        public String nameDa;
        public Set<String> includes = Set.of();
    }
}
