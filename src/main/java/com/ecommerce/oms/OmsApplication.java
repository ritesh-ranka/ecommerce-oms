package com.ecommerce.oms;

import com.ecommerce.oms.common.config.OmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.env.Environment;

/**
 * Entry point for the E-commerce Order Management Service.
 *
 * <p>A single deployable Spring Boot process (modular monolith). Feature packages are
 * self-contained and communicate service-to-service; see {@code docs/HLD.md} §3.
 */
@Slf4j
@SpringBootApplication
@EnableConfigurationProperties(OmsProperties.class)
public class OmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmsApplication.class, args);
    }

    /** Prints the handful of URLs a reviewer needs, so nothing has to be looked up. */
    @Bean
    ApplicationListener<ApplicationReadyEvent> startupBanner(Environment env) {
        return event -> {
            String port = env.getProperty("server.port", "8080");
            log.info("""

                    ==========================================================================
                     E-commerce Order Management Service is UP
                     Profile(s)   : {}
                     Swagger UI   : http://localhost:{}/swagger-ui.html
                     OpenAPI JSON : http://localhost:{}/v3/api-docs
                     H2 console   : http://localhost:{}/h2-console  (jdbc:h2:mem:oms / sa)
                     Demo logins  : admin@oms.dev | customer@oms.dev | staff.mum@oms.dev
                     Password     : Password@123
                    ==========================================================================""",
                    String.join(",", env.getActiveProfiles()), port, port, port);
        };
    }
}
