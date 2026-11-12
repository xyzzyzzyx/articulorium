package com.articulorium.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.articulorium.ai.Capability;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tests that the model provider is reached at its own address when the clients towards the platform
 * and the topology service are load balanced (the deployment's default).
 *
 * <p>Ollama is not a Consul service: a request to it that passed the load balancer would be
 * answered 503 ("no instances of ollama") without ever leaving the agent. The test runs the
 * application as deployed, with a local HTTP server standing in for Ollama.</p>
 */
@SpringBootTest(properties = {
    "articulorium.ai.load-balanced=true",
    "spring.ai.model.chat=ollama",
    "spring.ai.ollama.chat.model=test-model",
    "spring.ai.retry.max-attempts=1",
})
class OllamaRoutingTest {

    /** The answer of the test. */
    record Greeting(String text) {
    }

    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();

    private static HttpServer ollama;

    @Autowired
    private AgentModel model;

    @BeforeAll
    static void startOllama() throws IOException {
        ollama = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ollama.createContext("/api/chat", exchange -> {
            REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] body = """
                {"model": "test-model", "created_at": "2026-01-01T00:00:00Z", "done": true, "done_reason": "stop",
                 "message": {"role": "assistant", "content": "{\\"text\\": \\"hello\\"}"}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ollama.start();
    }

    @AfterAll
    static void stopOllama() {
        ollama.stop(0);
    }

    @DynamicPropertySource
    static void ollamaAddress(final DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:" + ollama.getAddress().getPort());
    }

    @Test
    void reachesTheModelProviderDirectly() {
        final Greeting greeting = model.ask(Capability.TOPICS, AgentPrompt.of("Greet.", "Say hello."), Greeting.class);

        assertThat(greeting.text()).isEqualTo("hello");
        assertThat(REQUESTS).singleElement().asString().contains("\"model\":\"test-model\"");
    }

}
