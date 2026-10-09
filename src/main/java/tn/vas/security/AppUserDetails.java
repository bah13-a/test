package tn.vas.security;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import tn.vas.domain.AppUser;

public record AppUserDetails(AppUser user) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return Arrays.stream(user.getRoles().split(",")).map(String::trim).filter(r -> !r.isEmpty())
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
    @Override public String getPassword() { return user.getPasswordHash(); }
    @Override public String getUsername() { return user.getUsername(); }
    @Override public boolean isAccountNonLocked() { return user.getLockedUntil() == null || user.getLockedUntil().isBefore(Instant.now()); }
    @Override public boolean isEnabled() { return user.isActive(); }
}
