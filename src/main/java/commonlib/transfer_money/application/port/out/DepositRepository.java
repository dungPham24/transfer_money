package commonlib.transfer_money.application.port.out;

import commonlib.transfer_money.domain.model.Deposit;

public interface DepositRepository {
    Deposit save(Deposit deposit);
}
