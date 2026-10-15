package com.zeroziba.orderstock.auth;

import com.zeroziba.orderstock.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    public record LoginSession(boolean current, Instant createdAt, Instant lastAccessedAt, Instant expiresAt) { }
    public record SessionPage(List<LoginSession> sessions, boolean truncated) { }
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public AccountService(UserRepository users, JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.users = users; this.jdbc = jdbc; this.passwords = passwords;
    }

    public boolean isCurrent(AuthenticatedUser principal) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) = 1 FROM app_users WHERE id=? AND auth_version=?", Boolean.class,
                principal.getId(), principal.getAuthVersion()));
    }

    @Transactional(readOnly = true)
    public SessionPage sessions(AuthenticatedUser principal, String currentId) {
        // Read metadata only: no security-context BLOB, cookies, IP or user-agent leaves the server.
        var rows = jdbc.query("""
                SELECT SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, EXPIRY_TIME
                FROM app_sessions WHERE PRINCIPAL_NAME=? AND EXPIRY_TIME>?
                ORDER BY (SESSION_ID=?) DESC, LAST_ACCESS_TIME DESC, PRIMARY_ID DESC LIMIT 101
                """, (rs, row) -> new LoginSession(currentId.equals(rs.getString(1)),
                Instant.ofEpochMilli(rs.getLong(2)), Instant.ofEpochMilli(rs.getLong(3)), Instant.ofEpochMilli(rs.getLong(4))),
                principal.getUsername(), System.currentTimeMillis(), currentId);
        return new SessionPage(List.copyOf(rows.subList(0, Math.min(rows.size(), 100))), rows.size() > 100);
    }

    @Transactional
    public void logoutOthers(AuthenticatedUser principal, String currentId) {
        requireCurrentLocked(principal);
        jdbc.update("DELETE FROM app_sessions WHERE PRINCIPAL_NAME=? AND SESSION_ID<>?", principal.getUsername(), currentId);
    }

    @Transactional
    public void changePassword(AuthenticatedUser principal, ChangePasswordRequest request) {
        if (request.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        AppUser user = requireCurrentLocked(principal);
        if (!passwords.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "현재 비밀번호가 올바르지 않습니다.");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "새 비밀번호는 현재 비밀번호와 다르게 입력하세요.");
        }
        user.changePassword(passwords.encode(request.newPassword()));
        users.flush();
        // JdbcTemplate joins the same JPA transaction. A failure rolls back the hash, version and deletions together.
        jdbc.update("DELETE FROM app_sessions WHERE PRINCIPAL_NAME=?", user.getEmail());
    }

    private AppUser requireCurrentLocked(AuthenticatedUser principal) {
        AppUser user = users.findByIdForUpdate(principal.getId()).orElseThrow(AccountService::expired);
        if (user.getAuthVersion() != principal.getAuthVersion()) throw expired();
        return user;
    }
    private static ApiException expired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "로그인이 만료됐어요. 다시 로그인해 주세요.");
    }
}
