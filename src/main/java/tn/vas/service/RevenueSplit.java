package tn.vas.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Répartition d'un montant facial TTC : taxes → part opérateur (% du HT) → part partenaire (% du reste) → fournisseur VAS.
 * La part fournisseur est le reliquat : la somme des composantes est toujours égale au montant facial.
 */
public record RevenueSplit(BigDecimal gross, BigDecimal taxes, BigDecimal operatorShare,
                           BigDecimal partnerShare, BigDecimal providerShare) {
    private static final int SCALE = 3;

    public static RevenueSplit compute(BigDecimal gross, BigDecimal taxPercent, BigDecimal operatorPercent,
                                       BigDecimal partnerPercent) {
        BigDecimal hundred = BigDecimal.valueOf(100);
        BigDecimal net = gross.multiply(hundred).divide(hundred.add(taxPercent), SCALE, RoundingMode.HALF_EVEN);
        BigDecimal taxes = gross.subtract(net).setScale(SCALE, RoundingMode.HALF_EVEN);
        BigDecimal op = net.multiply(operatorPercent).divide(hundred, SCALE, RoundingMode.HALF_EVEN);
        BigDecimal rest = net.subtract(op);
        BigDecimal partner = rest.multiply(partnerPercent).divide(hundred, SCALE, RoundingMode.HALF_EVEN);
        BigDecimal provider = rest.subtract(partner);
        return new RevenueSplit(gross.setScale(SCALE), taxes, op, partner, provider);
    }
}
