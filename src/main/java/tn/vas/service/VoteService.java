package tn.vas.service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.domain.Enums.MoOutcome;
import tn.vas.repo.Repos.*;

/** Vote à choix déclarés : « VOTE A » n'est valide que si A figure parmi les options du service ; un bulletin = une ligne vote_ballot. */
@Service
public class VoteService {
    private final VoteOptionRepo options;
    private final VoteBallotRepo ballots;
    private final Clock clock;

    public VoteService(VoteOptionRepo options, VoteBallotRepo ballots, Clock clock) {
        this.options = options;
        this.ballots = ballots;
        this.clock = clock;
    }

    public boolean configured(VasService svc) {
        return !options.findByServiceOrderById(svc).isEmpty();
    }

    /** @return null si le service n'a pas d'options déclarées (comportement générique) */
    @Transactional
    public Engines.Reply handle(VasService svc, MoMessage mo, String[] tokens, String lang) {
        List<VoteOption> opts = options.findByServiceOrderById(svc);
        if (opts.isEmpty()) return null;
        String choice = tokens.length > 1 ? tokens[1].toUpperCase(Locale.ROOT) : "";
        var opt = opts.stream().filter(o -> o.getCode().equalsIgnoreCase(choice)).findFirst().orElse(null);
        if (opt == null) {
            String list = opts.stream().map(VoteOption::getCode).collect(Collectors.joining(", "));
            return Engines.Reply.free(String.format(Messages.text(lang, Messages.INVALID_CHOICE), list), MoOutcome.INVALID_CHOICE);
        }
        var b = new VoteBallot();
        b.setService(svc);
        b.setMsisdn(mo.getMsisdn());
        b.setOptionCode(opt.getCode());
        b.setCreatedAt(clock.instant());
        b.setMoId(mo.getId());
        ballots.save(b);
        return Engines.Reply.paid(String.format(Messages.text(lang, Messages.VOTE_OK), opt.getLabel()));
    }
}
