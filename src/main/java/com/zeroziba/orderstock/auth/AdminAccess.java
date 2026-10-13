package com.zeroziba.orderstock.auth;

import com.zeroziba.orderstock.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AdminAccess {
    private final UserRepository users;
    public AdminAccess(UserRepository users) { this.users = users; }
    public void require(long id) {
        if (users.findById(id).map(user -> user.getRole() != UserRole.ADMIN).orElse(true)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "현재 관리자 권한이 필요합니다.");
        }
    }
}
