package com.gic.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Loopback transport fixtures only. These are not calls to an actual model. */
class OpenAiPlannerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ExplanationEngine.Analysis analysis = new ExplanationEngine(new RuleCatalog())
        .analyze(ExplanationFixtures.get("unknown_success_receipt"));
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    @AfterEach void stop() { if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); }
    private OpenAiPlanner start(int status, String response, long delay, Duration timeout) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(2); server.setExecutor(executor);
        server.createContext("/v1/responses", exchange -> {
            try {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-key");
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        return OpenAiPlanner.loopbackTest("fixture-key", "fixture-model", timeout,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"));
    }
    private String response(Object output) throws Exception {
        return json.writeValueAsString(Map.of("status", "completed", "output", output,
            "id", "resp_fixture", "model", "gpt-4.1-mini-2025-04-14", "service_tier", "default",
            "usage", Map.of("input_tokens", 123, "output_tokens", 45,
                "input_tokens_details", Map.of("cached_tokens", 100), "output_tokens_details", Map.of("reasoning_tokens", 5))));
    }
    private Object message(String plan) { return Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", plan))); }
    @Test void validResponseRequestsStrictSchemaWithoutToolsOrStorage() throws Exception {
        String plan = json.writeValueAsString(ExplanationPlan.baseline(analysis));
        var planner = start(200, response(List.of(Map.of("type", "reasoning"), message(plan))), 0, Duration.ofSeconds(2));
        var reply = planner.propose(analysis);
        assertThat(reply.inputTokens()).isEqualTo(123); assertThat(reply.outputTokens()).isEqualTo(45);
        assertThat(reply.modelRequest()).isTrue();
        assertThat(reply.usage().resolvedModel()).isEqualTo("gpt-4.1-mini-2025-04-14");
        assertThat(reply.usage().cachedInputTokens()).isEqualTo(100);
        assertThat(reply.usage().reasoningTokens()).isEqualTo(5);
        ExplanationPlan.parseAndValidate(reply.json(), analysis);
        var request = json.readTree(requestBody.get());
        assertThat(request.path("store").asBoolean()).isFalse();
        assertThat(request.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(request.has("tools")).isFalse(); assertThat(request.has("previous_response_id")).isFalse();
        assertThat(request.path("max_output_tokens").asInt()).isEqualTo(OpenAiPlanner.MAX_OUTPUT_TOKENS);
        assertThat(request.path("text").path("format").path("schema").path("required").toString()).contains("attributions");
        assertThat(requestBody.get()).contains("timeline", "relationDefinitions", "recordedAt");
        assertThat(request.path("service_tier").asText()).isEqualTo("default");
        assertThat(requestBody.get()).doesNotContain("counterparty", "requestFingerprint", "Idempotency-Key", "leaseToken");
        assertThat(requestBody.get()).doesNotContain("expectedRelations", "reviewGoals", "forbiddenClaims");
    }
    @Test void refusalIsNotRenderedAsAnExplanation() throws Exception {
        var planner = start(200, response(List.of(Map.of("type", "message", "content", List.of(Map.of("type", "refusal", "refusal", "no"))))), 0, Duration.ofSeconds(2));
        assertThatThrownBy(() -> planner.propose(analysis)).isInstanceOfSatisfying(ModelPlanner.Failure.class,
            ex -> { assertThat(ex.outcome()).isEqualTo("refused"); assertThat(ex.modelRequest()).isTrue();
                assertThat(ex.usage().inputTokens()).isEqualTo(123); assertThat(ex.usage().outputTokens()).isEqualTo(45); });
    }
    @Test void toolCallIsRejectedWithoutAnyDispatch() throws Exception {
        var planner = start(200, response(List.of(Map.of("type", "function_call", "name", "executeTrade", "arguments", "{}"))), 0, Duration.ofSeconds(2));
        assertThatThrownBy(() -> planner.propose(analysis)).isInstanceOfSatisfying(ModelPlanner.Failure.class,
            ex -> assertThat(ex.outcome()).isEqualTo("unexpected_output_type"));
    }
    @Test void httpErrorsNeverExposeRawProviderTextOrCredentials() throws Exception {
        var planner = start(429, "provider-error SECRET-FIXTURE-TEXT", 0, Duration.ofSeconds(2));
        assertThatThrownBy(() -> planner.propose(analysis)).isInstanceOfSatisfying(ModelPlanner.Failure.class,
            ex -> { assertThat(ex.outcome()).isEqualTo("http_429"); assertThat(ex.toString()).doesNotContain("SECRET-FIXTURE-TEXT", "fixture-key"); });
    }
    @Test void stalledResponseHasAnOverallDeadline() throws Exception {
        var planner = start(200, "{}", 600, Duration.ofMillis(100));
        long start = System.nanoTime();
        assertThatThrownBy(() -> planner.propose(analysis)).isInstanceOf(ModelPlanner.Failure.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
    }
    @Test void oversizedResponseIsBoundedBeforeBeingRetained() throws Exception {
        var planner = start(200, "x".repeat(70000), 0, Duration.ofSeconds(2));
        assertThatThrownBy(() -> planner.propose(analysis)).isInstanceOf(ModelPlanner.Failure.class);
    }
    @Test void incompleteResponseIsNotAccepted() throws Exception {
        var response = json.readTree(response(List.of()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) response).put("status", "incomplete");
        var planner = start(200, response.toString(), 0, Duration.ofSeconds(2));
        assertThatThrownBy(() -> planner.propose(analysis)).isInstanceOfSatisfying(ModelPlanner.Failure.class,
            ex -> { assertThat(ex.outcome()).isEqualTo("incomplete_response"); assertThat(ex.usage().cachedInputTokens()).isEqualTo(100); });
    }
    @Test void missingCredentialsCannotMakeANetworkRequest() {
        var planner = new OpenAiPlanner("", "", Duration.ofSeconds(1));
        assertThatThrownBy(() -> planner.propose(analysis)).isInstanceOfSatisfying(ModelPlanner.Failure.class,
            ex -> { assertThat(ex.outcome()).isEqualTo("not_configured"); assertThat(ex.modelRequest()).isFalse(); });
    }
    @Test void testEndpointCannotSendCredentialsToAnArbitraryHost() {
        assertThatThrownBy(() -> OpenAiPlanner.loopbackTest("fixture-key", "fixture", Duration.ofSeconds(1), URI.create("http://example.org/v1/responses")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void stalledBodyAfterHeadersAlsoHasAnOverallDeadline() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(1); server.setExecutor(executor);
        server.createContext("/v1/responses", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                Thread.sleep(700);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var planner = OpenAiPlanner.loopbackTest("fixture-key", "fixture", Duration.ofMillis(150),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"));
        long start = System.nanoTime();
        assertThatThrownBy(() -> planner.propose(analysis)).isInstanceOf(ModelPlanner.Failure.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
    }
    @Test void providerConcurrencyIsBoundedWithoutAnUnboundedWaitQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(2); server.setExecutor(executor);
        String valid = response(List.of(message(json.writeValueAsString(ExplanationPlan.baseline(analysis)))));
        server.createContext("/v1/responses", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes(); entered.countDown();
                if (!release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("fixture latch timeout");
                byte[] bytes = valid.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var planner = OpenAiPlanner.loopbackTest("fixture-key", "fixture", Duration.ofSeconds(5),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"));
        CompletableFuture<ModelPlanner.Reply> one = CompletableFuture.supplyAsync(() -> planner.propose(analysis));
        CompletableFuture<ModelPlanner.Reply> two = CompletableFuture.supplyAsync(() -> planner.propose(analysis));
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> planner.propose(analysis)).isInstanceOfSatisfying(ModelPlanner.Failure.class,
                failure -> { assertThat(failure.outcome()).isEqualTo("busy"); assertThat(failure.modelRequest()).isFalse(); });
        } finally { release.countDown(); }
        assertThat(one.get(5, TimeUnit.SECONDS).modelRequest()).isTrue();
        assertThat(two.get(5, TimeUnit.SECONDS).modelRequest()).isTrue();
    }
}
