package com.nyceats.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String allowedOrigins, String editKey, String osmUserAgent) {

    public List<String> allowedOriginList() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) return List.of();
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public boolean editKeyRequired() {
        return editKey != null && !editKey.isBlank();
    }
}
