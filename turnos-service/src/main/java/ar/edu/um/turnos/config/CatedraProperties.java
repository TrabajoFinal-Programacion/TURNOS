package ar.edu.um.turnos.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Integracion con la catedra (cuenta tecnica del proyecto). Todo por variables de entorno. */
@Validated
@ConfigurationProperties(prefix = "app.catedra")
public record CatedraProperties(
    @NotBlank String apiUrl,
    /** JWT tecnico. Nunca se loguea ni llega a la app. */
    @NotBlank String token,
    @NotBlank String groupId,
    @NotBlank String kafkaActionsTopic,
    @NotBlank String kafkaPhoneTopic,
    @NotBlank String kafkaConsumerGroup,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout,
    @Min(1) int maxAttempts,
    @NotNull Duration retryBackoff
) {
    @Override
    public String toString() {
        return "CatedraProperties[apiUrl=" + apiUrl + ", groupId=" + groupId + ", token=***]";
    }
}
