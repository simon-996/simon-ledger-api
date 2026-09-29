package com.simon.ledger.config.web;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.simon.ledger.common.ErrorCode;
import com.simon.ledger.common.exception.BusinessException;
import com.simon.ledger.entity.UserAccount;
import com.simon.ledger.mapper.UserAccountMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final UserAccountMapper userAccountMapper;

    @Value("${sa-token.token-name:Authorization}")
    private String tokenName;

    @Value("${ledger.web.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOriginPatterns;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        allowedOrigins().forEach(corsConfiguration::addAllowedOriginPattern);
        corsConfiguration.addAllowedHeader("*");
        corsConfiguration.addAllowedMethod("*");
        corsConfiguration.setMaxAge(3600L);
        corsConfiguration.setAllowCredentials(true);
        corsConfiguration.addExposedHeader(tokenName);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);
        return new CorsFilter(source);
    }

    private List<String> allowedOrigins() {
        return Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toList();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> SaRouter.match("/**").check(r -> {
                    if (isPublicInviteReadRequest()) {
                        return;
                    }
                    StpUtil.checkLogin();
                    if (!isAdminRequest()) {
                        requireExistingAccount();
                    }
                })))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/admin/auth/login",
                        "/health",
                        "/api/health",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/error"
                );
    }

    private boolean isPublicInviteReadRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.matches("^/api/invites/[^/]+$");
    }

    private boolean isAdminRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null && attributes.getRequest().getServletPath().startsWith("/api/admin/");
    }

    private void requireExistingAccount() {
        Long userId;
        try {
            userId = Long.valueOf(StpUtil.getLoginId().toString());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "普通账号登录态无效");
        }
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null || account.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或已删除");
        }
        if (!Integer.valueOf(1).equals(account.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
    }
}
