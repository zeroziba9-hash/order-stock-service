package com.zeroziba.orderstock.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccountVersionFilter extends OncePerRequestFilter {
    private final AccountService accounts;
    public AccountVersionFilter(AccountService accounts) { this.accounts = accounts; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                && !accounts.isCurrent(user)) {
            // Includes sessions saved by an in-flight login after a password change.
            new SecurityContextLogoutHandler().logout(request, response, authentication);
        }
        chain.doFilter(request, response);
    }
}
