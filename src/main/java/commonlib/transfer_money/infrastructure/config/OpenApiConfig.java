package commonlib.transfer_money.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI walletOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet & Transfer API")
                        .description("Fintech wallet service — idempotent transfers, double-entry ledger, concurrency-safe")
                        .version("1.0.0"));
    }
}