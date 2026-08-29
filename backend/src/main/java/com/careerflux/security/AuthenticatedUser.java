package com.careerflux.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.careerflux.user.Permission;
import com.careerflux.user.User;
import com.careerflux.user.UserRole;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The principal placed in the security context.
 *
 * <p>Carries both the role (as {@code ROLE_*}) and every permission the role
 * grants (as a bare authority), so {@code @PreAuthorize} can be written against
 * capabilities rather than against role names. Checking a permission survives a
 * role being renamed or a capability moving between roles; checking a role name
 * does not.
 *
 * <p>It also carries the institution id, which is the tenant key every
 * authorization decision starts from.
 */
public class AuthenticatedUser implements UserDetails {

    private final UUID userId;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;
    private final UserRole role;
    private final UUID institutionId;
    private final List<GrantedAuthority> authorities;

    public AuthenticatedUser(User user) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.enabled = user.isActive();
        this.role = user.getRole();
        this.institutionId = user.getInstitutionId();

        List<GrantedAuthority> granted = new ArrayList<>();
        granted.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        for (Permission permission : user.getRole().getPermissions()) {
            granted.add(new SimpleGrantedAuthority(permission.name()));
        }
        this.authorities = List.copyOf(granted);
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public UserRole getRole() {
        return role;
    }

    /** Null for a platform administrator, who belongs to no college. */
    public UUID getInstitutionId() {
        return institutionId;
    }

    public boolean hasPermission(Permission permission) {
        return role.has(permission);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
