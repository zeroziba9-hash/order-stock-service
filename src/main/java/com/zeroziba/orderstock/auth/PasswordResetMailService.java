package com.zeroziba.orderstock.auth;

import com.zeroziba.orderstock.common.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

@Service
public class PasswordResetMailService {
    private final PasswordResetSettings settings;private final PasswordResetService resets;private final ObjectProvider<JavaMailSender> senders;private final MeterRegistry metrics;
    private final ThreadPoolTaskExecutor executor;
    public PasswordResetMailService(PasswordResetSettings settings,PasswordResetService resets,ObjectProvider<JavaMailSender> senders,MeterRegistry metrics,@Qualifier("passwordResetMailExecutor") ThreadPoolTaskExecutor executor){this.settings=settings;this.resets=resets;this.senders=senders;this.metrics=metrics;this.executor=executor;}
    public void request(String email){
        if(!settings.enabled()||senders.getIfAvailable()==null)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"RESET_UNAVAILABLE","현재 이메일 복구를 사용할 수 없어요. 잠시 후 다시 확인하세요.");
        // Every syntactically valid address takes the same admission path, before account lookup/SMTP.
        try{executor.execute(()->deliverSafely(email));}
        catch(TaskRejectedException failure){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"RESET_BUSY","메일 요청이 많아요. 잠시 후 다시 요청하세요.");}
    }
    private void deliverSafely(String email){
        try{deliver(email);}catch(RuntimeException failure){metrics.counter("stockroom.password.reset.mail","outcome","failed").increment();LoggerFactory.getLogger(getClass()).warn("password_reset_delivery_failed type={}",failure.getClass().getSimpleName());}
    }
    private void deliver(String email){
        var issue=resets.issue(email);if(issue==null)return;
        var message=new SimpleMailMessage();message.setFrom(settings.from());message.setTo(issue.email());message.setSubject("STOCKROOM 비밀번호 재설정");
        message.setText("비밀번호를 재설정하려면 아래 링크를 열어주세요. 15분 동안 한 번만 사용할 수 있어요.\n\n"+settings.link(issue.token())+"\n\n직접 요청하지 않았다면 이 메일을 무시하세요. 비밀번호는 변경되지 않았어요.");
        try{senders.getObject().send(message);metrics.counter("stockroom.password.reset.mail","outcome","accepted").increment();}
        catch(MailException failure){resets.revokeFailedDelivery(issue);metrics.counter("stockroom.password.reset.mail","outcome","failed").increment();LoggerFactory.getLogger(getClass()).warn("password_reset_mail_failed type={}",failure.getClass().getSimpleName());}
    }
}
