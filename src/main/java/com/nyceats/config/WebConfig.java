package com.nyceats.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AppProperties props;

    public WebConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(props.allowedOriginList().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /** Optional write protection: only active when EDIT_KEY is set. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
                if (!props.editKeyRequired() || !WRITE_METHODS.contains(req.getMethod())) return true;
                String supplied = req.getHeader("X-Edit-Key");
                boolean ok = supplied != null && MessageDigest.isEqual(
                        supplied.getBytes(StandardCharsets.UTF_8),
                        props.editKey().getBytes(StandardCharsets.UTF_8));
                if (!ok) {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType("application/json");
                    res.getWriter().write("{\"message\":\"A valid edit key is required to make changes.\"}");
                }
                return ok;
            }
        }).addPathPatterns("/api/**");
    }
}
