package com.zeroziba.orderstock.auth;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AuthenticatedUser implements UserDetails, CredentialsContainer, Serializable {
    private static final long serialVersionUID = 1L;
    private final long id;
    private final String email;
    private String passwordHash;
    private final UserRole role;
    private final long authVersion;

    public AuthenticatedUser(long id, String email, String passwordHash) {
        this(id, email, passwordHash, UserRole.USER);
    }

    public AuthenticatedUser(long id, String email, String passwordHash, UserRole role) {
        this(id, email, passwordHash, role, 0);
    }

    public AuthenticatedUser(long id, String email, String passwordHash, UserRole role, long authVersion) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.authVersion = authVersion;
    }

    public long getId() { return id; }
    public long getAuthVersion() { return authVersion; }
    @Override public String getUsername() { return email; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return role == UserRole.ADMIN
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
    @Override public void eraseCredentials() { passwordHash = null; }
    public UserResponse response() { return new UserResponse(id, email, role); }
}
