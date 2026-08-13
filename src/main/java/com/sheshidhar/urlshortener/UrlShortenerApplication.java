package com.sheshidhar.urlshortener;

import com.sheshidhar.urlshortener.config.AnalyticsProperties;
import com.sheshidhar.urlshortener.config.SecurityProperties;
import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({UrlShortenerProperties.class, AnalyticsProperties.class, SecurityProperties.class})
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
