package commonlib.transfer_money.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI walletOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet & Transfer API")
                        .description("""
                                Fintech wallet service — idempotent fund transfers with double-entry ledger integrity.

                                **Key design decisions:**
                                - `SELECT FOR UPDATE` with fixed UUID lock ordering prevents deadlocks under concurrent transfers.
                                - Every transfer writes one DEBIT + one CREDIT ledger entry atomically with the balance update.
                                - Two-layer idempotency: application-level key check (sequential) + DB `UNIQUE` constraint \
                                in a `REQUIRES_NEW` sub-transaction (concurrent race).
                                - `CHECK (balance >= 0)` in the DB schema is the hard stop against overdraft, \
                                even if application code has a bug.
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("dungPham24")
                                .url("https://github.com/dungPham24/transfer_money")))
                .servers(List.of(
                        new Server().url("/").description("Local / Docker Compose (port 8080)")
                ));
    }
}