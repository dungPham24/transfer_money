package commonlib.transfer_money;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TransferMoneyApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransferMoneyApplication.class, args);
    }

}
