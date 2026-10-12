package com.zeroziba.orderstock.auth;

public record UserResponse(long userId, String email) {
    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail());
    }
}
