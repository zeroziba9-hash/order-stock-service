package com.zeroziba.orderstock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class TestAccounts {
    public static final String PASSWORD = "TestPass123!";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(PASSWORD);
    private TestAccounts() { }

    public static long create(JdbcTemplate jdbc, String email) {
        jdbc.update("INSERT INTO app_users (email, password_hash) VALUES (?, ?)", email, PASSWORD_HASH);
        return jdbc.queryForObject("SELECT id FROM app_users WHERE email = ?", Long.class, email);
    }
}
