package dk.trustworks.intranet.aggregates.crm.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewsApiClientTest {
    NewsApiClient client;
    final LocalDate day = LocalDate.of(2026, 9, 13);
    @BeforeEach void setup() {
        client = new NewsApiClient(); client.mapper = new ObjectMapper(); client.config = new ClientNewsConfig();
        client.config.apiKey = Optional.of("fake-test-token");
    }

    @Test void rejectsProviderErrorsMalformedResponsesAndPaginationMismatch() {
        for (String json : List.of("{\"error\":\"echo fake-test-token\"}", "{}", "not-json",
                "{\"articles\":{\"results\":[],\"pages\":1,\"page\":2}}")) {
            var failure = assertThrows(NewsApiClient.NewsFailure.class,
                    () -> client.parse(json.getBytes(StandardCharsets.UTF_8), 1));
            assertEquals("INVALID_RESPONSE", failure.getMessage()); assertNull(failure.getCause());
        }
        assertEquals(0, client.parse("{\"articles\":{\"results\":[],\"pages\":0,\"page\":1}}".getBytes(), 1).articles().size());
    }

    @Test void destinationAndRequestAreFixedAndCredentialIsAbsentFromUrl() throws Exception {
        HttpRequest request = client.request(List.of("Digitaliseringsstyrelsen", "Danish Agency for Digital Government"), day, day, 1);
        assertEquals("https://eventregistry.org/api/v1/article/getArticles", request.uri().toString());
        assertEquals("POST", request.method()); assertFalse(request.uri().toString().contains("fake-test-token"));
        var body = new NewsApiClient.LimitedBodySubscriber();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { body.onSubscribe(subscription); }
            public void onNext(ByteBuffer item) { body.onNext(List.of(item)); }
            public void onError(Throwable throwable) { body.onError(throwable); }
            public void onComplete() { body.onComplete(); }
        });
        var json = client.mapper.readTree(body.getBody().toCompletableFuture().join());
        assertEquals("or", json.path("keywordOper").asText());
        assertEquals("phrase", json.path("keywordSearchMode").asText());
        assertEquals("body,title", json.path("keywordLoc").asText());
        assertEquals("fake-test-token", json.path("apiKey").asText());
    }

    @Test void stalledBodyCancelsTransportAtWholeRequestDeadline() throws Exception {
        client.http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        CompletableFuture<HttpResponse<byte[]>> stalled = mock(CompletableFuture.class);
        when(stalled.get(25, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        when(client.http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(stalled);
        assertThrows(TimeoutException.class, () -> client.send(client.request(List.of("Example"), day, day, 1)));
        verify(stalled).cancel(true);
    }

    @Test void retryConsumesAnotherBudgetUnitAndNeverReturnsSuccessForTransportFailure() throws Exception {
        NewsApiClient fake = spy(client);
        doThrow(new TimeoutException("fake-test-token must not escape")).when(fake).send(any());
        AtomicInteger reserved = new AtomicInteger();
        var failure = assertThrows(NewsApiClient.NewsFailure.class,
                () -> fake.fetch(List.of("Example"), day, day, 1, () -> { reserved.incrementAndGet(); return true; }));
        assertEquals(2, reserved.get()); assertEquals("TIMEOUT_OR_IO", failure.getMessage()); assertNull(failure.getCause());
        verify(fake, times(2)).send(any());
    }

    @Test void noBudgetMeansNoHttpAndOversizedBodyCancelsPublisher() {
        client.http = mock(HttpClient.class);
        assertEquals(NewsApiClient.Failure.BUDGET_OR_LEASE, assertThrows(NewsApiClient.NewsFailure.class,
                () -> client.fetch(List.of("Example"), day, day, 1, () -> false)).code);
        verifyNoInteractions(client.http);
        var subscriber = new NewsApiClient.LimitedBodySubscriber();
        Flow.Subscription subscription = mock(Flow.Subscription.class); subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(NewsApiClient.MAX_RESPONSE_BYTES + 1)));
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
        verify(subscription).cancel();
    }
}
