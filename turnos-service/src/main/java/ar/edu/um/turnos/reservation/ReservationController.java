package ar.edu.um.turnos.reservation;

import ar.edu.um.turnos.reservation.dto.CancelReservationRequest;
import ar.edu.um.turnos.reservation.dto.ReservationPage;
import ar.edu.um.turnos.reservation.dto.ReservationResponse;
import ar.edu.um.turnos.reservation.dto.StartReservationRequest;
import ar.edu.um.turnos.reservation.dto.SubmitPhoneRequest;
import ar.edu.um.turnos.security.SecurityUtils;
import ar.edu.um.turnos.web.error.ApiException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reservas del usuario autenticado. El usuario siempre sale del JWT; ningun endpoint acepta
 * un userId, externalPatientId o groupId enviado por el cliente.
 */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /**
     * Inicia una reserva (hold + confirmacion inicial). La app puede enviar Idempotency-Key
     * (por ejemplo un UUID por toque de "Reservar"): si repite el pedido por un timeout, recibe el mismo proceso.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse start(@Valid @RequestBody StartReservationRequest request,
                                     @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 100)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key debe tener entre 1 y 100 caracteres");
        }
        return service.start(currentUser(), request, idempotencyKey);
    }

    @GetMapping
    public ReservationPage list(@RequestParam(required = false) List<ReservationStatus> status,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return service.list(currentUser(), status, page, size);
    }

    @GetMapping("/{id}")
    public ReservationResponse get(@PathVariable Long id) {
        return service.get(currentUser(), id);
    }

    @PostMapping("/{id}/phone")
    public ReservationResponse submitPhone(@PathVariable Long id, @Valid @RequestBody SubmitPhoneRequest request) {
        return service.submitPhone(currentUser(), id, request.phoneNumber());
    }

    @PostMapping("/{id}/cancel")
    public ReservationResponse cancel(@PathVariable Long id,
                                      @Valid @RequestBody(required = false) CancelReservationRequest request) {
        return service.cancel(currentUser(), id, request == null ? null : request.reason());
    }

    /** Reconcilia mis reservas con el listado de la catedra y devuelve el resultado. */
    @PostMapping("/sync")
    public ReservationPage synchronize() {
        return service.synchronize(currentUser());
    }

    private static Long currentUser() {
        return SecurityUtils.currentUserId()
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Token sin usuario"));
    }
}
