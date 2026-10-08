package tn.vas.service;

import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tn.vas.domain.AuditLog;
import tn.vas.repo.Repos.AuditRepo;

@Service
public class AuditService {
    private final AuditRepo repo;
    private final Clock clock;

    public AuditService(AuditRepo repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public void log(String action, String target, String detail) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        var l = new AuditLog();
        l.setActor(a == null ? "system" : a.getName());
        l.setAction(action);
        l.setTarget(target);
        l.setDetail(detail != null && detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        l.setAt(clock.instant());
        repo.save(l);
    }
}
