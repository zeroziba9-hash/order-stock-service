package com.zeroziba.orderstock.auth;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/password-reset")
public class PasswordResetController {
    public record Config(boolean enabled) { }
    public record Request(@NotBlank @Email @Size(max=254) String email) { }
    public record Confirm(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,@NotBlank @Size(min=8,max=72) String newPassword) { }
    public record Receipt(String message) { }
    private final PasswordResetSettings settings;private final PasswordResetMailService mail;private final PasswordResetService resets;
    public PasswordResetController(PasswordResetSettings settings,PasswordResetMailService mail,PasswordResetService resets){this.settings=settings;this.mail=mail;this.resets=resets;}
    @GetMapping("/config") public Config config(){return new Config(settings.enabled());}
    @PostMapping("/request") public ResponseEntity<Receipt> request(@Valid @RequestBody Request body){
        mail.request(body.email());return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new Receipt("등록된 이메일이라면 재설정 안내가 발송돼요. 수신함과 스팸함을 확인하세요. 메일이 없으면 1분 후 다시 요청할 수 있어요."));
    }
    @PostMapping("/confirm") public ResponseEntity<Void> confirm(@Valid @RequestBody Confirm body,HttpServletRequest request,HttpServletResponse response,Authentication authentication){
        resets.confirm(body.token(),body.newPassword());new SecurityContextLogoutHandler().logout(request,response,authentication);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
