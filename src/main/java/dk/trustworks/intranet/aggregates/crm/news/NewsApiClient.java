package dk.trustworks.intranet.aggregates.crm.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/** Fixed destination; credentials only in POST body, never URL, logs, persistence or errors. */
@ApplicationScoped
public class NewsApiClient {
    static final URI ENDPOINT = URI.create("https://eventregistry.org/api/v1/article/getArticles");
    static final int PAGE_SIZE = 50;
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    @Inject ClientNewsConfig config;
    @Inject ObjectMapper mapper;
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public enum Failure { CONFIGURATION, BUDGET_OR_LEASE, AUTHENTICATION, RATE_LIMIT, HTTP_ERROR,
        INVALID_RESPONSE, RESPONSE_TOO_LARGE, TIMEOUT_OR_IO, INTERRUPTED, INCOMPLETE }
    public static class NewsFailure extends RuntimeException {
        final Failure code;
        NewsFailure(Failure code) { super(code.name()); this.code = code; }
    }
    public record Page(List<JsonNode> articles, int page, int pages) { }

    public Page fetch(List<String> aliases, LocalDate from, LocalDate to, int page,
                      BooleanSupplier reserveRequest) {
        if (!config.configured()) throw new NewsFailure(Failure.CONFIGURATION);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!reserveRequest.getAsBoolean()) throw new NewsFailure(Failure.BUDGET_OR_LEASE);
            try {
                HttpResponse<byte[]> response = send(request(aliases, from, to, page));
                int status = response.statusCode();
                if (status == 401 || status == 403) throw new NewsFailure(Failure.AUTHENTICATION);
                if (status == 429 || status >= 500) {
                    if (attempt == 0) {
                        // Honour short Retry-After values; long backoffs are handled by durable hourly recovery.
                        int wait = retryAfter(response.headers().firstValue("Retry-After").orElse("1"));
                        if (wait <= 2) { Thread.sleep(wait * 1000L); continue; }
                    }
                    throw new NewsFailure(status == 429 ? Failure.RATE_LIMIT : Failure.HTTP_ERROR);
                }
                if (status != 200) throw new NewsFailure(Failure.HTTP_ERROR);
                return parse(response.body(), page);
            } catch (NewsFailure e) { throw e; }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NewsFailure(Failure.INTERRUPTED);
            } catch (Exception e) {
                if (attempt == 1) throw new NewsFailure(Failure.TIMEOUT_OR_IO);
                // No exception message/cause is propagated: HTTP diagnostics can contain request bodies.
            }
        }
        throw new NewsFailure(Failure.TIMEOUT_OR_IO);
    }

    HttpRequest request(List<String> aliases, LocalDate from, LocalDate to, int page) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "getArticles"); payload.put("resultType", "articles");
        payload.put("keyword", aliases); payload.put("keywordOper", "or");
        payload.put("keywordSearchMode", "phrase");
        payload.put("keywordLoc", "body,title"); payload.put("lang", List.of("dan", "eng"));
        payload.put("dateStart", from.toString()); payload.put("dateEnd", to.toString());
        payload.put("articlesPage", page); payload.put("articlesCount", PAGE_SIZE);
        payload.put("articlesSortBy", "date"); payload.put("articlesSortByAsc", false);
        payload.put("includeArticleBody", true); payload.put("includeArticleConcepts", false);
        payload.put("includeArticleCategories", false); payload.put("includeArticleEventUri", true);
        payload.put("isDuplicateFilter", "skipDuplicates");
        payload.put("apiKey", config.apiKey().orElseThrow());
        return HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload))).build();
    }

    HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        // Java 21's request timeout can end when headers arrive. This deadline covers the
        // entire body and cancellation aborts a stalled publisher instead of stranding a worker.
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request,
                ignored -> new LimitedBodySubscriber());
        try { return pending.get(25, TimeUnit.SECONDS); }
        catch (TimeoutException | InterruptedException e) { pending.cancel(true); throw e; }
        catch (ExecutionException e) {
            if (e.getCause() instanceof NewsFailure failure) throw failure;
            throw e;
        }
    }

    Page parse(byte[] body, int expectedPage) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || root.has("error")) throw new NewsFailure(Failure.INVALID_RESPONSE);
            JsonNode result = root.path("articles");
            if (!result.path("results").isArray() || !result.path("pages").canConvertToInt()
                    || !result.path("page").canConvertToInt() || result.path("page").asInt() != expectedPage)
                throw new NewsFailure(Failure.INVALID_RESPONSE);
            List<JsonNode> articles = new ArrayList<>();
            result.path("results").forEach(articles::add);
            int pages = result.path("pages").asInt();
            if (pages < 0 || articles.size() > PAGE_SIZE || (!articles.isEmpty() && pages < expectedPage))
                throw new NewsFailure(Failure.INVALID_RESPONSE);
            return new Page(List.copyOf(articles), expectedPage, pages);
        } catch (NewsFailure e) { throw e; }
        catch (Exception e) { throw new NewsFailure(Failure.INVALID_RESPONSE); }
    }
    static int retryAfter(String text) {
        try { return Math.max(1, Integer.parseInt(text)); }
        catch (NumberFormatException e) { return 60; }
    }

    /** Bound memory before allocating a whole response. send() supplies the whole-body deadline. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_RESPONSE_BYTES) {
                    subscription.cancel(); result.completeExceptionally(new NewsFailure(Failure.RESPONSE_TOO_LARGE)); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(new NewsFailure(Failure.TIMEOUT_OR_IO)); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
