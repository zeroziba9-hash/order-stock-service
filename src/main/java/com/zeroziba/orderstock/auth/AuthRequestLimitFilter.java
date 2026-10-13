package com.zeroziba.orderstock.auth;

import com.zeroziba.orderstock.common.ApiExceptionHandler.ApiError;
import com.zeroziba.orderstock.monitoring.AuthMetrics;
import com.zeroziba.orderstock.monitoring.AuthMetrics.Endpoint;
import com.zeroziba.orderstock.monitoring.AuthMetrics.Outcome;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class AuthRequestLimitFilter extends OncePerRequestFilter {
    private final AuthRequestLimiter limiter;
    private final AuthClientAddress clients;
    private final AuthMetrics metrics;
    private final ObjectMapper mapper;
    public AuthRequestLimitFilter(AuthRequestLimiter limiter,AuthClientAddress clients,AuthMetrics metrics,ObjectMapper mapper) {
        this.limiter=limiter; this.clients=clients; this.metrics=metrics; this.mapper=mapper;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !request.getMethod().equals("POST") || !(path.equals("/auth/login") || path.equals("/auth/register"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        Endpoint endpoint=request.getRequestURI().endsWith("/login") ? Endpoint.LOGIN : Endpoint.REGISTER;
        var admission=limiter.begin(endpoint,clients.resolve(request),endpoint==Endpoint.LOGIN ? request.getParameter("email") : null);
        if (!admission.allowed()) {
            metrics.rejected(endpoint,admission.reason());
            response.setStatus(429); response.setHeader("Retry-After",Long.toString(admission.retryAfter()));
            response.setHeader("Cache-Control","no-store"); response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getWriter(),new ApiError("AUTH_RATE_LIMITED","요청이 많아 잠시 제한됐어요. 안내된 시간이 지난 뒤 다시 시도하세요."));
            return;
        }
        int status=500;
        try { chain.doFilter(request,response); status=response.getStatus(); }
        finally {
            limiter.finish(admission,status);
            metrics.result(endpoint,status>=500 ? Outcome.ERROR : status>=200 && status<300 ? Outcome.SUCCESS : Outcome.FAILURE);
        }
    }
}
