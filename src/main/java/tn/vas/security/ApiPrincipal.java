package tn.vas.security;

import tn.vas.domain.ApiClient;

public record ApiPrincipal(ApiClient client) {
    @Override public String toString() { return "api:" + client.getName(); }
}
