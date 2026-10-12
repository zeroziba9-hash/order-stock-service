package com.zeroziba.orderstock.auth;

import com.zeroziba.orderstock.common.ApiException;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        String email = AppUserDetailsService.normalizeEmail(request.email());
        try {
            AppUser user = userRepository.saveAndFlush(new AppUser(email, passwordEncoder.encode(request.password())));
            return UserResponse.from(user);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 등록된 이메일입니다.");
        }
    }
}
