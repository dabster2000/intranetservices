package dk.trustworks.intranet.aggregates.crm.news;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static dk.trustworks.intranet.aggregates.crm.news.ClientNewsDTO.*;

/** Conservative, deterministic selection; facts never turn into contact edits or inferred sales leads. */
@ApplicationScoped
public class ClientNewsMatcher {
    public List<String> aliases(ClientNewsRepository.Identity client) {
        String name = plain(client.name(), 200).strip();
        String base = name.replaceFirst("(?i)\\s+(a/s|aps|p/s|a\\.s\\.|as|ltd\\.?|inc\\.?)$", "").strip();
        Set<String> aliases = new LinkedHashSet<>();
        if (!base.isBlank()) aliases.add(base);
        if (isNovo(client)) aliases.add("Novo Nordisk");
        if (isVattenfall(client)) aliases.add("Vattenfall");
        if (isDigst(client)) {
            aliases.add("Digitaliseringsstyrelsen"); aliases.add("Danish Agency for Digital Government");
        }
        return List.copyOf(aliases);
    }

    public Optional<StoredItem> select(ClientNewsRepository.Identity client, JsonNode article, Instant now) {
        String title = plain(article.path("title").asText(""), 500);
        String body = plain(article.path("body").asText(""), 120_000);
        String normalizedTitle = normal(title);
        String lead = normal(body.substring(0, Math.min(6000, body.length())));
        String identityLead = isNovo(client) ? lead.replaceAll("novo nordisk (foundation|fonden|fond)", " ") : lead;
        if (title.isBlank() || aliases(client).stream().noneMatch(a -> containsPhrase(normalizedTitle, normal(a))
                || containsPhrase(identityLead, normal(a))))
            return Optional.empty();
        if (isNovo(client) && (containsPhrase(normalizedTitle, "novo nordisk foundation")
                || normalizedTitle.contains("novo nordisk fond") || normalizedTitle.contains("novo holdings")))
            return Optional.empty();
        if (isDigst(client) && Pattern.compile("(?iu)grønland|greenland|naalakkersuisut|gronland")
                .matcher(title + " " + body.substring(0, Math.min(1200, body.length()))).find())
            return Optional.empty();
        String url = safeUrl(article.path("url").asText(""));
        if (url == null) return Optional.empty();
        Instant published = publication(article);
        if (published == null || published.isAfter(now.plusSeconds(300))) return Optional.empty();
        // Market tickers and share-price forecasts swamp pharma clients without helping account teams.
        if (matches(normalizedTitle, "aktiekurs|kursmål|kursmal|stock price|price target|shares (rise|fall)|" +
                "technical analysis|technical signal|moving average|dividend yield|stock forecast|analyst rating|" +
                "aktien stiger|aktien falder|børsluk|borsluk|wall street|insider trading|stock market"))
            return Optional.empty();
        Category category = category(normalizedTitle);
        if (category == Category.OTHER && isDigst(client) && matches(normalizedTitle + " " + lead,
                "altid|mitid|digital identitet|digital identity|alderskontrol|age verification"))
            category = Category.DIGITAL_TRANSFORMATION;
        // Require a meaningful development in the headline, not an unrelated article that happens to name a client.
        if (category == Category.OTHER && !matches(normalizedTitle,
                "regnskab|årsresultat|arsresultat|annual result|earnings|profit|omsætning|omsaetning|" +
                        "revenue|launch|lancering|lancerer|godkend|approv|udbud|tender|contract award|" +
                        "wins contract|vinder kontrakt|investigat|undersøg|undersog|lawsuit|retssag")) return Optional.empty();
        Scope scope = isVattenfall(client) && !containsPhrase(normalizedTitle, "vattenfall a s")
                ? Scope.GROUP : Scope.COMPANY;
        String source = plain(article.path("source").path("title").asText(""), 200);
        if (source.isBlank()) source = URI.create(url).getHost();
        String providerId = article.path("uri").asText("");
        String id = hash(providerId.isBlank() ? url : providerId);
        String event = article.path("eventUri").asText("");
        String storyKey = event.isBlank() ? "title:" + hash(normalizedTitle) : "event:" + hash(event);
        return Optional.of(new StoredItem(new Item(id, title, url, source, published,
                excerpt(body), category, scope), storyKey));
    }

    public List<StoredItem> latest(Collection<StoredItem> existing, Collection<StoredItem> incoming) {
        List<StoredItem> candidates = new ArrayList<>(incoming);
        candidates.addAll(existing);
        candidates.sort(Comparator.comparing((StoredItem n) -> n.item().publishedAt()).reversed()
                .thenComparing(n -> n.item().id()));
        List<StoredItem> kept = new ArrayList<>();
        for (StoredItem next : candidates) {
            boolean duplicate = kept.stream().anyMatch(old -> old.item().id().equals(next.item().id())
                    || old.item().url().equals(next.item().url()) || old.storyKey().equals(next.storyKey())
                    || sameHeadline(old.item().title(), next.item().title()));
            if (!duplicate) kept.add(next);
            if (kept.size() == 6) break;
        }
        return List.copyOf(kept);
    }

    static Category category(String text) {
        if (matches(text, "direktør|direktor|chief|\\bceo\\b|\\bcio\\b|\\bcto\\b|\\bcfo\\b|" +
                "appoint|udnævn|udnaevn|ledelse|leadership|bestyrels|board|tiltræd|tiltraed|fratræd|fratraed"))
            return Category.LEADERSHIP;
        if (matches(text, "digital|\\bai\\b|kunstig intelligens|artificial intelligence|cloud|cyber|" +
                "signalsystem|signalfejl|signalling|signaling|ertms|it system|dataplatform|data platform|" +
                "data breach|databrud|data centre|data center|datacenter|stolen.*token|automatis|automatiz")) return Category.DIGITAL_TRANSFORMATION;
        if (matches(text, "omstruktur|restructur|fyring|fyrer|afskedig|layoff|lay off|job cut|" +
                "nedlæg|nedlaeg|lukker|bankrupt|konkurs")) return Category.RESTRUCTURING;
        if (matches(text, "opkøb|opkob|acqui|fusion|merger|partner|samarbejd|alliance|aftale|agreement")) return Category.PARTNERSHIP;
        if (matches(text, "invest|funding|finansier|expan|udvid|bygger|construction|billion|milliard|million")) return Category.INVESTMENT;
        if (matches(text, "regulat|lovgiv|compliance|directive|direktiv|myndighed|tilsyn")) return Category.REGULATION;
        if (matches(text, "strateg|transformation|modernis|omstilling|program|ambition")) return Category.STRATEGY;
        return Category.OTHER;
    }
    static Instant publication(JsonNode article) {
        for (String field : List.of("dateTimePub", "dateTime", "date")) {
            String text = article.path(field).asText("");
            try { return Instant.parse(text); } catch (Exception ignored) { }
            try { return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { }
            try { return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant(); } catch (Exception ignored) { }
        }
        return null;
    }
    static String safeUrl(String raw) {
        try {
            if (raw.length() > 2048) return null;
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || !Set.of("https", "http").contains(uri.getScheme())) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (!host.contains(".") || host.endsWith(".local") || host.endsWith(".localhost")
                    || host.matches("[\\d.]+") || host.contains(":")) return null;
            String query = uri.getRawQuery();
            if (query != null) query = Arrays.stream(query.split("&"))
                    .filter(p -> !p.toLowerCase(Locale.ROOT).matches("(utm_[^=]*|fbclid|gclid)=.*"))
                    .collect(Collectors.joining("&"));
            return uri.getScheme() + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                    + (uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (query == null || query.isBlank() ? "" : "?" + query);
        } catch (Exception e) { return null; }
    }
    static String excerpt(String body) {
        if (body.isBlank()) return "";
        String[] words = body.split("\\s+");
        String result = String.join(" ", Arrays.copyOf(words, Math.min(20, words.length)));
        return result.substring(0, Math.min(400, result.length())) + (words.length > 20 ? "…" : "");
    }
    static String plain(String text, int max) {
        if (text == null) return "";
        // Truncate before processing untrusted input. A linear scan avoids quadratic regex
        // backtracking on malformed HTML such as a long run of '<' without closing tags.
        String bounded = text.substring(0, Math.min(text.length(), max));
        StringBuilder out = new StringBuilder(bounded.length());
        boolean tag = false, space = false;
        for (int i = 0; i < bounded.length(); i++) {
            char ch = bounded.charAt(i);
            if (ch == '<') { tag = true; ch = ' '; }
            else if (ch == '>') { tag = false; ch = ' '; }
            else if (tag) continue;
            if (Character.isWhitespace(ch) || Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT) {
                if (!space) out.append(' ');
                space = true;
            } else { out.append(ch); space = false; }
        }
        return out.toString().strip();
    }
    static String normal(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFKC)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
    static boolean containsPhrase(String text, String phrase) {
        return !phrase.isBlank() && (" " + text + " ").contains(" " + phrase + " ");
    }
    static boolean sameHeadline(String a, String b) {
        Set<String> left = new HashSet<>(List.of(normal(a).split(" ")));
        Set<String> right = new HashSet<>(List.of(normal(b).split(" ")));
        if (left.equals(right)) return true;
        if (Math.min(left.size(), right.size()) < 7) return false;
        Set<String> union = new HashSet<>(left); union.addAll(right);
        left.retainAll(right);
        return (double) left.size() / union.size() >= .9;
    }
    static boolean matches(String text, String pattern) { return Pattern.compile(pattern).matcher(text).find(); }
    static boolean isNovo(ClientNewsRepository.Identity c) {
        return "24256790".equals(c.cvr()) || ((c.cvr() == null || c.cvr().isBlank())
                && Set.of("novo nordisk", "novo nordisk a s").contains(normal(c.name())));
    }
    static boolean isVattenfall(ClientNewsRepository.Identity c) { return "21311332".equals(c.cvr()) || normal(c.name()).equals("vattenfall a s") || normal(c.name()).equals("vattenfall"); }
    static boolean isDigst(ClientNewsRepository.Identity c) { return normal(c.name()).equals("digitaliseringsstyrelsen"); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA256 unavailable"); }
    }
}
