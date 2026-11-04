package ar.edu.um.turnos.catalogo;

import ar.edu.um.turnos.config.CatalogoProperties;
import ar.edu.um.turnos.security.SecurityUtils;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Cliente del servicio de catalogo. Propaga el JWT del usuario que origino el request
 * (ADR 0001): catalogo valida firma, vigencia y rol, y sabe que usuario hizo la consulta.
 * Solo se usa dentro de un request HTTP autenticado; los procesos en segundo plano no lo llaman.
 */
@Component
public class CatalogoClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogoClient.class);

    private final RestClient restClient;
    private final CatalogoProperties properties;

    public CatalogoClient(RestClient.Builder builder, CatalogoProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());
        this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    /** @return vacio si el profesional no existe en el catalogo. */
    public Optional<ProfessionalAgenda> professional(Long professionalId) {
        String token = SecurityUtils.currentBearerToken()
            .orElseThrow(() -> new IllegalStateException("Llamada a catalogo sin JWT de usuario"));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                return Optional.ofNullable(restClient.get()
                    .uri("/api/professionals/{id}", professionalId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(ProfessionalAgenda.class));
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                    return Optional.empty();
                }
                // 400/401/403: no es transitorio. Se informa como catalogo no disponible para esta operacion.
                throw new CatalogoUnavailableException("Catalogo rechazo la consulta: HTTP " + e.getStatusCode().value(), e);
            } catch (HttpServerErrorException e) {
                // 503 CATALOG_NOT_SYNCHRONIZED u otro 5xx
                last = e;
            } catch (ResourceAccessException e) {
                last = e;
            }
            log.warn("Catalogo: intento {}/{} fallido ({})", attempt, properties.maxAttempts(),
                last.getClass().getSimpleName());
        }
        throw new CatalogoUnavailableException("El servicio de catalogo no esta disponible", last);
    }
}
