package commonlib.transfer_money;

import commonlib.transfer_money.api.dto.CreateWalletRequest;
import commonlib.transfer_money.api.dto.PagedResponse;
import commonlib.transfer_money.api.dto.TransactionHistoryResponse;
import commonlib.transfer_money.api.dto.WalletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WalletIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // maxConnections sized well above any test's thread count — without this, WebTestClient's
    // default reactor-netty connection pool serializes concurrent .exchange() calls from
    // separate Java threads, which looks identical to a server-side hang/deadlock in a test.
    private static final ConnectionProvider CONNECTION_PROVIDER =
            ConnectionProvider.builder("test-pool").maxConnections(50).build();

    @LocalServerPort int port;
    WebTestClient http;

    // Built manually (not @Autowired) — this Servlet-based app has no reactive-web
    // autoconfiguration that would register a WebTestClient bean bound to the random port.
    @BeforeEach
    void setUpHttpClient() {
        http = WebTestClient
                .bindToServer(new ReactorClientHttpConnector(HttpClient.create(CONNECTION_PROVIDER)))
                .baseUrl("http://localhost:" + port)
                // Default WebTestClient response timeout is 5s — too tight for a freshly
                // started Testcontainers Postgres + cold JVM/connection-pool warmup.
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ── POST /api/v1/wallets ────────────────────────────────────────────────

    @Test
    void createWallet_returnsCreatedWithZeroBalance() {
        WalletResponse body = http.post().uri("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWalletRequest("Alice", "USD"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(WalletResponse.class)
                .returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.id()).isNotNull();
        assertThat(body.ownerName()).isEqualTo("Alice");
        assertThat(body.currency()).isEqualTo("USD");
        assertThat(body.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createWallet_invalidCurrency_returns400() {
        http.post().uri("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWalletRequest("Bob", "FAKE"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void createWallet_blankOwnerName_returns400() {
        http.post().uri("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWalletRequest("", "USD"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── GET /api/v1/wallets/{id} ────────────────────────────────────────────

    @Test
    void getWallet_existingWallet_returnsBalance() {
        WalletResponse created = createWallet("Carol", "EUR");

        WalletResponse body = http.get().uri("/api/v1/wallets/{id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WalletResponse.class)
                .returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(created.id());
        assertThat(body.currency()).isEqualTo("EUR");
    }

    @Test
    void getWallet_unknownId_returns404() {
        http.get().uri("/api/v1/wallets/00000000-0000-0000-0000-000000000000")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── GET /api/v1/wallets/{id}/transactions ───────────────────────────────

    @Test
    void getTransactions_newWallet_returnsEmptyPage() {
        WalletResponse wallet = createWallet("Dave", "USD");

        PagedResponse<TransactionHistoryResponse> body = http.get()
                .uri("/api/v1/wallets/{id}/transactions?page=0&size=20", wallet.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PagedResponse<TransactionHistoryResponse>>() {})
                .returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.content()).isEmpty();
        assertThat(body.totalElements()).isZero();
        assertThat(body.page()).isZero();
        assertThat(body.size()).isEqualTo(20);
    }

    @Test
    void getTransactions_unknownWallet_returns404() {
        http.get().uri("/api/v1/wallets/00000000-0000-0000-0000-000000000000/transactions")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getTransactions_pageSizeExceedsMax_returns400() {
        WalletResponse wallet = createWallet("Eve", "USD");

        http.get().uri("/api/v1/wallets/{id}/transactions?size=999", wallet.id())
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private WalletResponse createWallet(String ownerName, String currency) {
        WalletResponse body = http.post().uri("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWalletRequest(ownerName, currency))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(WalletResponse.class)
                .returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }
}
