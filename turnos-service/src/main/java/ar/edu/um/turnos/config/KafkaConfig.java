package ar.edu.um.turnos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /**
     * Reintentos acotados de un evento cuyo procesamiento fallo (base caida, conflicto de version).
     * Agotados, se confirma el offset para no bloquear el topic; la recuperacion periodica
     * reconcilia el proceso con la catedra por REST.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler((record, exception) -> log.warn(
            "Evento de turnos descartado tras reintentos (offset {}): {}. La recuperacion reconciliara el proceso.",
            record.offset(), exception.getMessage()), new FixedBackOff(2000L, 3));
    }
}
