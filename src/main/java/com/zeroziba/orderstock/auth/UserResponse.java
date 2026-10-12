package com.zeroziba.orderstock.auth;

public record UserResponse(long userId, String email, UserRole role) {
    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole());
    }
}
