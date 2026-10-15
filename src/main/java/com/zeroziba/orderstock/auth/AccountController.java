package com.zeroziba.orderstock.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AccountController {
    private final AccountService accounts;
    public AccountController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping("/sessions")
    public AccountService.SessionPage sessions(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        return accounts.sessions(user, request.getSession(false).getId());
    }

    @PostMapping("/sessions/logout-others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutOthers(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        accounts.logoutOthers(user, request.getSession(false).getId());
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody ChangePasswordRequest body,
                         HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        accounts.changePassword(user, body);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
}
