package ar.edu.um.turnos.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
            ? List.of()
            : allowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
