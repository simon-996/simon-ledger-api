package com.simon.ledger.config.web;

import com.simon.ledger.mapper.UserAccountMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.CorsFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebCorsConfigurationTests {

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml.example"})
    void adminLoginPreflightAllowsDeployedAdminOrigin(String resource) throws Exception {
        MockHttpServletResponse response = preflight(resource, "https://ladmin.simon996.com");

        assertEquals(200, response.getStatus());
        assertEquals("https://ladmin.simon996.com", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
        assertEquals("POST", response.getHeader("Access-Control-Allow-Methods"));
        assertTrue(response.getHeader("Access-Control-Allow-Headers").contains("simon-ledger"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml.example"})
    void adminLoginPreflightRejectsUntrustedOrigin(String resource) throws Exception {
        MockHttpServletResponse response = preflight(resource, "https://untrusted.example");

        assertEquals(403, response.getStatus());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }

    private MockHttpServletResponse preflight(String resource, String origin) throws Exception {
        Object origins = new YamlPropertySourceLoader()
                .load("cors", new ClassPathResource(resource))
                .get(0).getProperty("ledger.web.allowed-origin-patterns");
        WebConfig config = new WebConfig(mock(UserAccountMapper.class));
        ReflectionTestUtils.setField(config, "allowedOriginPatterns", origins);
        ReflectionTestUtils.setField(config, "tokenName", "simon-ledger");
        CorsFilter filter = config.corsFilter();

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/admin/auth/login");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type,simon-ledger");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
