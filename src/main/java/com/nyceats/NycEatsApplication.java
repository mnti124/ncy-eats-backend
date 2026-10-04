package com.nyceats;

import com.nyceats.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class NycEatsApplication {

    public static void main(String[] args) {
        SpringApplication.run(NycEatsApplication.class, args);
    }
}
