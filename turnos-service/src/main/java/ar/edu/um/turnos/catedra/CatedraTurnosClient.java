package ar.edu.um.turnos.catedra;

import ar.edu.um.turnos.catedra.dto.CatedraDtos.Appointment;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.CancelRequest;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.CancelResponse;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.ConfirmHoldRequest;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.ConfirmHoldResponse;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.HoldRequest;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.HoldResponse;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.OccupancyResponse;
import ar.edu.um.turnos.config.CatedraProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Cliente REST de turnos de la catedra, autenticado con el JWT tecnico (que nunca sale del backend).
 *
 * Politica de reintentos (contrato 18.4: un timeout no prueba que la operacion fallo):
 * - Lecturas (ocupaciones, listado): se reintentan.
 * - Confirmacion inicial: se reintenta; si el primer intento habia llegado, la catedra responde
 *   RESERVATION_PROCESS_ALREADY_CONFIRMED y el servicio lo trata como exito.
 * - Cancelacion: se reintenta; es idempotente segun el contrato.
 * - Creacion de hold: NO se reintenta. No hay forma de consultar un hold por REST, y un segundo
 *   intento chocaria con el propio hold huerfano (SLOT_ALREADY_HELD). El hold huerfano vence solo.
 */
@Component
public class CatedraTurnosClient {

    private static final Logger log = LoggerFactory.getLogger(CatedraTurnosClient.class);

    private final RestClient restClient;
    private final CatedraProperties properties;
    private final ObjectMapper objectMapper;

    public CatedraTurnosClient(RestClient.Builder builder, CatedraProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());
        this.restClient = builder
            .baseUrl(properties.apiUrl())
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    public OccupancyResponse occupancies(Long professionalId, LocalDate from, LocalDate to) {
        return call("ocupaciones", true, () -> restClient.get()
            .uri(u -> u.path("/api/appointment-occupancies")
                .queryParam("professionalId", professionalId)
                .queryParam("from", from)
                .queryParam("to", to)
                .build())
            .retrieve()
            .body(OccupancyResponse.class));
    }

    public HoldResponse createHold(HoldRequest request) {
        return call("crear hold", false, () -> restClient.post()
            .uri("/api/appointment-holds")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(HoldResponse.class));
    }

    public ConfirmHoldResponse confirmHold(String holdId, ConfirmHoldRequest request) {
        return call("confirmar hold", true, () -> restClient.post()
            .uri("/api/appointment-holds/{holdId}/confirm", holdId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(ConfirmHoldResponse.class));
    }

    /** Reservas de la cuenta tecnica para un usuario final (filtro externalPatientId exacto). */
    public List<Appointment> appointmentsOf(String externalPatientId) {
        return call("listar reservas", true, () -> restClient.get()
            .uri(u -> u.path("/api/appointments")
                .queryParam("externalPatientId", externalPatientId)
                .queryParam("page", 0)
                .queryParam("size", 200)
                .build())
            .retrieve()
            .body(new ParameterizedTypeReference<List<Appointment>>() {}));
    }

    public CancelResponse cancel(String reservationProcessId, String reason) {
        return call("cancelar", true, () -> restClient.post()
            .uri("/api/appointments/{id}/cancel", reservationProcessId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new CancelRequest(reason))
            .retrieve()
            .body(CancelResponse.class));
    }

    private <T> T call(String operation, boolean retryable, Supplier<T> request) {
        int attempts = retryable ? properties.maxAttempts() : 1;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                T result = request.get();
                if (result == null) {
                    throw new CatedraUnavailableException("Respuesta vacia de la catedra (" + operation + ")", null);
                }
                return result;
            } catch (HttpClientErrorException e) {
                throw toApiException(e); // 4xx: decision funcional, no se reintenta
            } catch (ResourceAccessException | HttpServerErrorException e) {
                last = e;
                log.warn("Catedra {}: intento {}/{} fallido ({})", operation, attempt, attempts,
                    e.getClass().getSimpleName());
                if (attempt < attempts) {
                    sleep(properties.retryBackoff().toMillis() * attempt);
                }
            }
        }
        throw new CatedraUnavailableException("La catedra no respondio (" + operation + ")", last);
    }

    private CatedraApiException toApiException(HttpClientErrorException e) {
        String code = null;
        String detail = null;
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsString());
            if (body != null) {
                code = body.hasNonNull("code") ? body.get("code").asText() : null;
                detail = body.hasNonNull("detail") ? body.get("detail").asText() : null;
            }
        } catch (Exception ignored) {
            // cuerpo vacio o no JSON (por ejemplo, 401 de Spring Security)
        }
        return new CatedraApiException(e.getStatusCode().value(), code, detail);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatedraUnavailableException("Interrumpido esperando para reintentar", e);
        }
    }
}
