package commonlib.transfer_money;

import commonlib.transfer_money.api.dto.CreateWalletRequest;
import commonlib.transfer_money.api.dto.PagedResponse;
import commonlib.transfer_money.api.dto.TransactionHistoryResponse;
import commonlib.transfer_money.api.dto.WalletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

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

    @Autowired TestRestTemplate http;

    // ── POST /api/v1/wallets ────────────────────────────────────────────────

    @Test
    void createWallet_returnsCreatedWithZeroBalance() {
        var request = new CreateWalletRequest("Alice", "USD");

        ResponseEntity<WalletResponse> response =
                http.postForEntity("/api/v1/wallets", request, WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        WalletResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isNotNull();
        assertThat(body.ownerName()).isEqualTo("Alice");
        assertThat(body.currency()).isEqualTo("USD");
        assertThat(body.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createWallet_invalidCurrency_returns400() {
        var request = new CreateWalletRequest("Bob", "FAKE");

        ResponseEntity<String> response =
                http.postForEntity("/api/v1/wallets", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWallet_blankOwnerName_returns400() {
        var request = new CreateWalletRequest("", "USD");

        ResponseEntity<String> response =
                http.postForEntity("/api/v1/wallets", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── GET /api/v1/wallets/{id} ────────────────────────────────────────────

    @Test
    void getWallet_existingWallet_returnsBalance() {
        WalletResponse created = createWallet("Carol", "EUR");

        ResponseEntity<WalletResponse> response =
                http.getForEntity("/api/v1/wallets/" + created.id(), WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(created.id());
        assertThat(response.getBody().currency()).isEqualTo("EUR");
    }

    @Test
    void getWallet_unknownId_returns404() {
        ResponseEntity<String> response =
                http.getForEntity("/api/v1/wallets/00000000-0000-0000-0000-000000000000", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── GET /api/v1/wallets/{id}/transactions ───────────────────────────────

    @Test
    void getTransactions_newWallet_returnsEmptyPage() {
        WalletResponse wallet = createWallet("Dave", "USD");

        ResponseEntity<PagedResponse<TransactionHistoryResponse>> response = http.exchange(
                "/api/v1/wallets/" + wallet.id() + "/transactions?page=0&size=20",
                HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PagedResponse<TransactionHistoryResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.content()).isEmpty();
        assertThat(body.totalElements()).isZero();
        assertThat(body.page()).isZero();
        assertThat(body.size()).isEqualTo(20);
    }

    @Test
    void getTransactions_unknownWallet_returns404() {
        ResponseEntity<String> response = http.getForEntity(
                "/api/v1/wallets/00000000-0000-0000-0000-000000000000/transactions",
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getTransactions_pageSizeExceedsMax_returns400() {
        WalletResponse wallet = createWallet("Eve", "USD");

        ResponseEntity<String> response = http.getForEntity(
                "/api/v1/wallets/" + wallet.id() + "/transactions?size=999",
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private WalletResponse createWallet(String ownerName, String currency) {
        var body = http.postForEntity(
                "/api/v1/wallets",
                new CreateWalletRequest(ownerName, currency),
                WalletResponse.class);
        assertThat(body.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return body.getBody();
    }
}