package com.zeroziba.orderstock.auth;

import com.zeroziba.orderstock.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {
    public record Issue(long userId,String email,String token) { }
    private final JdbcTemplate jdbc;private final UserRepository users;private final PasswordEncoder passwords;private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public PasswordResetService(JdbcTemplate jdbc,UserRepository users,PasswordEncoder passwords,Clock clock){this.jdbc=jdbc;this.users=users;this.passwords=passwords;this.clock=clock;}
    @Transactional
    public Issue issue(String email){
        var ids=jdbc.queryForList("SELECT id FROM app_users WHERE email=?",Long.class,AppUserDetailsService.normalizeEmail(email));if(ids.isEmpty())return null;
        var user=users.findByIdForUpdate(ids.get(0)).orElseThrow();
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        var rows=jdbc.query("SELECT issued_at,window_started_at,request_count FROM password_resets WHERE user_id=?",
            (row,index)->new Object[]{row.getObject(1,LocalDateTime.class),row.getObject(2,LocalDateTime.class),row.getInt(3)},user.getId());
        LocalDateTime window=now;int count=1;
        if(!rows.isEmpty()){
            var row=rows.get(0);if(((LocalDateTime)row[0]).plusSeconds(60).isAfter(now))return null;
            if(((LocalDateTime)row[1]).plusHours(1).isAfter(now)){window=(LocalDateTime)row[1];count=(int)row[2]+1;if(count>5)return null;}
        }
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
            INSERT INTO password_resets(user_id,token_hash,auth_version,issued_at,expires_at,window_started_at,request_count)
            VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE token_hash=VALUES(token_hash),auth_version=VALUES(auth_version),
            issued_at=VALUES(issued_at),expires_at=VALUES(expires_at),window_started_at=VALUES(window_started_at),request_count=VALUES(request_count)
            """,user.getId(),hash(token),user.getAuthVersion(),now,now.plusMinutes(15),window,count);
        return new Issue(user.getId(),user.getEmail(),token);
    }
    @Transactional
    public void revokeFailedDelivery(Issue issue){jdbc.update("UPDATE password_resets SET token_hash=NULL,expires_at=NULL WHERE user_id=? AND token_hash=?",issue.userId(),hash(issue.token()));}
    @Transactional
    public void confirm(String token,String password){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw invalid();
        if(password==null||password.length()<8||password.length()>72||password.isBlank()||password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","비밀번호는 8자 이상, UTF-8 기준 72바이트 이하로 입력하세요.");
        var digest=hash(token);var ids=jdbc.queryForList("SELECT user_id FROM password_resets WHERE token_hash=?",Long.class,digest);if(ids.isEmpty())throw invalid();
        var user=users.findByIdForUpdate(ids.get(0)).orElseThrow(PasswordResetService::invalid);
        var rows=jdbc.query("SELECT token_hash,auth_version,expires_at FROM password_resets WHERE user_id=? FOR UPDATE",
            (row,index)->new Object[]{row.getString(1),row.getLong(2),row.getObject(3,LocalDateTime.class)},user.getId());
        if(rows.isEmpty())throw invalid();var row=rows.get(0);
        if(!digest.equals(row[0])||(long)row[1]!=user.getAuthVersion()||row[2]==null||!((LocalDateTime)row[2]).toInstant(ZoneOffset.UTC).isAfter(clock.instant()))throw invalid();
        user.changePassword(passwords.encode(password));users.flush();
        jdbc.update("UPDATE password_resets SET token_hash=NULL,expires_at=NULL WHERE user_id=?",user.getId());
        jdbc.update("DELETE FROM app_sessions WHERE PRINCIPAL_NAME=?",user.getEmail());
    }
    @Scheduled(initialDelayString="${app.password-reset.cleanup-interval-ms:3600000}",fixedDelayString="${app.password-reset.cleanup-interval-ms:3600000}")
    public void cleanup(){jdbc.update("DELETE FROM password_resets WHERE issued_at<? ORDER BY issued_at LIMIT 1000",LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).minusDays(1));}
    private static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}catch(NoSuchAlgorithmException failure){throw new IllegalStateException("SHA-256 unavailable");}}
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"RESET_INVALID","링크가 유효하지 않거나 만료됐어요. 비밀번호 재설정을 다시 요청하세요.");}
}
