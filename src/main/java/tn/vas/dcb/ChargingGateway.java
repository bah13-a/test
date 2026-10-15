package tn.vas.dcb;

import java.math.BigDecimal;
import tn.vas.domain.Operator;

/** API de facturation de l'opérateur (débit sur la facture ou le crédit prépayé de l'abonné). */
public interface ChargingGateway {
    record Result(boolean ok, String providerRef, String error) {}

    Result charge(Operator op, String msisdn, BigDecimal amount, String txId, String description);

    Result refund(Operator op, String providerRef, BigDecimal amount, String txId);
}
