package tn.vas.dcb;

import java.math.BigDecimal;
import tn.vas.domain.Operator;

/** Opérateur simulé (dev, tests, recette sans contrat DCB) : tout numéro se terminant par 0000 a un solde insuffisant. */
public class SimulatedChargingGateway implements ChargingGateway {
    @Override
    public Result charge(Operator op, String msisdn, BigDecimal amount, String txId, String description) {
        if (msisdn.endsWith("0000")) return new Result(false, null, "INSUFFICIENT_BALANCE");
        return new Result(true, "SIM-" + txId, null);
    }

    @Override
    public Result refund(Operator op, String providerRef, BigDecimal amount, String txId) {
        return new Result(true, providerRef, null);
    }
}
