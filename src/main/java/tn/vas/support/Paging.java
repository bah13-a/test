package tn.vas.support;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

/** Pagination des listes d'administration : paramètres page (0..) et size (défaut 50, max 500) ; total dans l'en-tête X-Total-Count. */
public final class Paging {
    public static final int DEFAULT_SIZE = 50, MAX_SIZE = 500;

    private Paging() {}

    public static PageRequest req(Integer page, Integer size, Sort sort) {
        int s = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        return PageRequest.of(page == null ? 0 : Math.max(0, page), s, sort);
    }

    public static <T> ResponseEntity<List<T>> of(Page<T> p) {
        return ResponseEntity.ok().header("X-Total-Count", Long.toString(p.getTotalElements())).body(p.getContent());
    }

    /** Pagination en mémoire pour les listes de configuration (quelques dizaines de lignes). */
    public static <T> ResponseEntity<List<T>> slice(List<T> all, Integer page, Integer size) {
        var r = req(page, size, Sort.unsorted());
        int from = (int) Math.min((long) r.getPageNumber() * r.getPageSize(), all.size());
        int to = Math.min(from + r.getPageSize(), all.size());
        return ResponseEntity.ok().header("X-Total-Count", Integer.toString(all.size())).body(all.subList(from, to));
    }
}
