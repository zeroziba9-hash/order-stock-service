package com.zeroziba.orderstock.auth;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetSettings {
    private final boolean enabled;
    private final String baseUrl,from;
    public PasswordResetSettings(Environment env){
        enabled=env.getProperty("app.password-reset.enabled",Boolean.class,false);
        baseUrl=env.getProperty("app.password-reset.base-url","").strip();from=env.getProperty("app.password-reset.from","").strip();
        if(!enabled)return;
        try {
            var uri=URI.create(baseUrl);
            if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                ||!(uri.getPath().isEmpty()||uri.getPath().equals("/"))||!java.util.Set.of("http","https").contains(uri.getScheme()))throw new IllegalArgumentException();
            var address=new jakarta.mail.internet.InternetAddress(from,true);address.validate();
            if(!address.getAddress().equals(from)||address.getPersonal()!=null||from.contains("\r")||from.contains("\n"))throw new IllegalArgumentException();
            if(env.getProperty("spring.mail.host","").isBlank())throw new IllegalArgumentException();
            if(env.matchesProfiles("prod") && (!uri.getScheme().equals("https")||!uri.getHost().matches("(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")||uri.getHost().toLowerCase(java.util.Locale.ROOT).endsWith(".localhost")||from.endsWith(".test")
                ||!env.getProperty("spring.mail.properties.mail.smtp.auth",Boolean.class,false)
                ||!env.getProperty("spring.mail.properties.mail.smtp.starttls.required",Boolean.class,false)
                ||!env.getProperty("spring.mail.properties.mail.smtp.starttls.enable",Boolean.class,false)
                ||env.getProperty("spring.mail.username","").isBlank()||env.getProperty("spring.mail.password","").isBlank()))throw new IllegalArgumentException();
        }catch(Exception failure){throw new IllegalStateException("Password reset requires a trusted base URL, sender and SMTP settings; prod requires HTTPS and authenticated STARTTLS.");}
    }
    public boolean enabled(){return enabled;}
    public String link(String token){return baseUrl.replaceAll("/$","")+"/reset.html#token="+token;}
    public String from(){return from;}
}
