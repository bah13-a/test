package tn.vas.service;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.config.VasProperties;
import tn.vas.domain.Enums.DlrBillingRule;
import tn.vas.domain.Operator;
import tn.vas.domain.ShortCode;
import tn.vas.repo.Repos.OperatorRepo;
import tn.vas.repo.Repos.ShortCodeRepo;

/**
 * Synchronise les opérateurs et short codes déclarés dans la configuration du profil (vas.operators) avec la base.
 * Mode "create-only" (défaut pro) : la configuration s'applique à la première synchronisation de chaque opérateur (y compris ceux
 * préchargés par la migration V2), puis le back-office fait foi ; mode "overwrite" : la configuration écrase toujours la base. Les secrets SMPP ne passent jamais par ici (ils vivent dans Jasmin).
 */
@Component
@Order(10)
public class OperatorSync {
    private static final Logger log = LoggerFactory.getLogger(OperatorSync.class);
    private final VasProperties props;
    private final OperatorRepo operators;
    private final ShortCodeRepo shortCodes;

    public OperatorSync(VasProperties props, OperatorRepo operators, ShortCodeRepo shortCodes) {
        this.props = props;
        this.operators = operators;
        this.shortCodes = shortCodes;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    @Transactional
    public void sync() {
        if (props.operators() == null) return;
        boolean overwrite = "overwrite".equalsIgnoreCase(props.operatorsSync());
        for (var c : props.operators()) {
            Operator op = operators.findByCode(c.code()).orElseGet(() -> {
                var n = new Operator();
                n.setCode(c.code());
                n.setName(c.name() == null ? c.code() : c.name());
                n.setMsisdnPrefixes("");
                n.setJasminConnector(c.jasminConnector() == null ? "smppc_" + c.code().toLowerCase() : c.jasminConnector());
                log.info("opérateur créé depuis la configuration : {}", c.code());
                return n;
            });
            // première synchronisation : la configuration s'applique ; ensuite le back-office fait foi (sauf mode overwrite)
            if (overwrite || !op.isConfigApplied()) {
                if (c.name() != null) op.setName(c.name());
                if (c.msisdnPrefixes() != null) op.setMsisdnPrefixes(c.msisdnPrefixes());
                if (c.jasminConnector() != null) op.setJasminConnector(c.jasminConnector());
                if (c.dlrBillingRule() != null) op.setDlrBillingRule(DlrBillingRule.valueOf(c.dlrBillingRule()));
                if (c.maxTps() != null) op.setMaxTps(c.maxTps());
                op.setConfigApplied(true);
            }
            final Operator saved = operators.save(op);
            if (c.shortCodes() != null) {
                Arrays.stream(c.shortCodes().split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(num -> {
                    if (shortCodes.findByNumberAndOperator(num, saved).isEmpty()) {
                        var sc = new ShortCode();
                        sc.setNumber(num);
                        sc.setOperator(saved);
                        shortCodes.save(sc);
                        log.info("short code {} créé pour {}", num, saved.getCode());
                    }
                });
            }
        }
    }
}
