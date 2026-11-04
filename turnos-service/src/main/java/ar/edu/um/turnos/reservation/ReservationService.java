package ar.edu.um.turnos.reservation;

import ar.edu.um.turnos.availability.AvailabilityService;
import ar.edu.um.turnos.availability.Slot;
import ar.edu.um.turnos.availability.SlotCalculator;
import ar.edu.um.turnos.availability.SlotStatus;
import ar.edu.um.turnos.catalogo.ProfessionalAgenda;
import ar.edu.um.turnos.catedra.CatedraApiException;
import ar.edu.um.turnos.catedra.CatedraTurnosClient;
import ar.edu.um.turnos.catedra.CatedraUnavailableException;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.CancelResponse;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.ConfirmHoldRequest;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.ConfirmHoldResponse;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.HoldRequest;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.HoldResponse;
import ar.edu.um.turnos.config.ReservationProperties;
import ar.edu.um.turnos.reservation.PhoneSubmissionPublisher.MessagingUnavailableException;
import ar.edu.um.turnos.reservation.dto.ReservationPage;
import ar.edu.um.turnos.reservation.dto.ReservationResponse;
import ar.edu.um.turnos.reservation.dto.StartReservationRequest;
import ar.edu.um.turnos.user.User;
import ar.edu.um.turnos.user.UserRepository;
import ar.edu.um.turnos.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Casos de uso de reservas del usuario final. Toda operacion recibe el userId del JWT
 * y busca el proceso con findByIdAndUserId: un usuario nunca ve ni modifica procesos ajenos
 * (responde 404, sin revelar si el id existe).
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("appointmentDate"), Sort.Order.desc("startTime"),
        Sort.Order.desc("id"));

    private final ReservationProcessRepository repository;
    private final ReservationStore store;
    private final UserRepository users;
    private final AvailabilityService availability;
    private final CatedraTurnosClient catedra;
    private final PhoneSubmissionPublisher phonePublisher;
    private final ReservationReconciler reconciler;
    private final ReservationProperties properties;
    private final Clock clock;

    public ReservationService(ReservationProcessRepository repository, ReservationStore store, UserRepository users,
                              AvailabilityService availability, CatedraTurnosClient catedra,
                              PhoneSubmissionPublisher phonePublisher, ReservationReconciler reconciler,
                              ReservationProperties properties, Clock clock) {
        this.repository = repository;
        this.store = store;
        this.users = users;
        this.availability = availability;
        this.catedra = catedra;
        this.phonePublisher = phonePublisher;
        this.reconciler = reconciler;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ iniciar reserva

    /**
     * 1. Idempotencia: si la app repite el pedido con la misma Idempotency-Key, se devuelve el mismo proceso.
     * 2. Validacion con el catalogo VIGENTE (JWT del usuario propagado): profesional, categoria y turno.
     * 3. Registro local (CREATED), hold en la catedra (HELD) y confirmacion inicial (WAITING_FOR_PHONE).
     * Ninguna llamada remota se hace dentro de una transaccion.
     */
    public ReservationResponse start(Long userId, StartReservationRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<ReservationProcess> existing = repository.findByUserIdAndClientRequestId(userId, idempotencyKey);
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
        }
        User user = users.findById(userId).filter(User::isActivated)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Usuario inexistente"));

        availability.validateDate(request.date());
        ProfessionalAgenda agenda = availability.requireProfessional(request.professionalId());
        if (!agenda.isBookable()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROFESSIONAL_NOT_AVAILABLE",
                "El profesional o su categoria no estan habilitados");
        }
        Instant now = clock.instant();
        LocalDateTime nowLocal = LocalDateTime.now(clock.withZone(properties.zone()));
        Slot slot = SlotCalculator.find(agenda, request.date(), request.startTime(), nowLocal, now)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SLOT",
                "El horario no corresponde a la agenda vigente del profesional"));
        if (slot.status() == SlotStatus.PAST) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SLOT_IN_PAST", "El horario ya paso");
        }

        String firstName = patientName(request.patientFirstName(), user.getFirstName(), "patientFirstName");
        String lastName = patientName(request.patientLastName(), user.getLastName(), "patientLastName");

        ReservationProcess process;
        try {
            process = store.insert(ReservationProcess.create(userId, user.getExternalId(), idempotencyKey,
                agenda.id(), agenda.firstName(), agenda.lastName(), agenda.categoryName(),
                request.date(), slot.startTime(), slot.endTime(), firstName, lastName, now));
        } catch (DataIntegrityViolationException e) {
            // Dos pedidos simultaneos con la misma Idempotency-Key: gana el primero.
            return repository.findByUserIdAndClientRequestId(userId, idempotencyKey)
                .map(this::toResponse)
                .orElseThrow(() -> e);
        }

        Long id = process.getId();
        HoldResponse hold;
        try {
            hold = catedra.createHold(new HoldRequest(agenda.id(), request.date(), slot.startTime(),
                user.getExternalId()));
        } catch (CatedraApiException e) {
            store.update(id, p -> p.failed(e.getCode(), "La catedra rechazo el hold", clock.instant()));
            throw holdError(e);
        } catch (CatedraUnavailableException e) {
            // No se sabe si el hold se creo. No se reintenta (chocaria con el propio hold) y no hay forma
            // de consultarlo: el proceso se cierra y el hold huerfano, si existe, vence solo.
            store.update(id, p -> p.failed("HOLD_UNKNOWN", "La catedra no respondio al pedir el turno",
                clock.instant()));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CATEDRA_UNAVAILABLE",
                "El servicio de turnos no respondio. Intente nuevamente en unos minutos.");
        }
        store.update(id, p -> p.holdCreated(hold.holdId(), hold.reservationProcessId(), hold.expiresAt(),
            clock.instant()));
        log.info("Hold {} obtenido para el proceso local {} (catedra {})", hold.holdId(), id,
            hold.reservationProcessId());

        return toResponse(confirmInitial(id));
    }

    /**
     * Confirmacion inicial del hold. Tambien la usa la recuperacion para procesos que quedaron en HELD.
     * Un timeout deja el proceso en HELD: la recuperacion vuelve a intentar mientras el hold este vigente.
     */
    public ReservationProcess confirmInitial(Long id) {
        ReservationProcess p = repository.findById(id).orElseThrow();
        if (p.getStatus() != ReservationStatus.HELD) {
            return p;
        }
        try {
            ConfirmHoldResponse r = catedra.confirmHold(p.getHoldId(),
                new ConfirmHoldRequest(p.getExternalPatientId(), p.getPatientFirstName(), p.getPatientLastName()));
            return store.update(id, x -> x.confirmAccepted(r.expiresAt(), clock.instant()));
        } catch (CatedraApiException e) {
            String code = e.getCode() == null ? "HTTP_" + e.getStatus() : e.getCode();
            return switch (code) {
                // Un intento anterior (con respuesta perdida) ya habia llegado: es exito.
                case "RESERVATION_PROCESS_ALREADY_CONFIRMED" ->
                    store.update(id, x -> x.confirmAccepted(null, clock.instant()));
                case "HOLD_EXPIRED", "HOLD_NOT_FOUND" -> store.update(id, x -> x.expired(code, clock.instant()));
                default -> store.update(id, x -> x.failed(code, "La catedra rechazo la confirmacion",
                    clock.instant()));
            };
        } catch (CatedraUnavailableException e) {
            log.warn("Confirmacion inicial del proceso {} sin respuesta; queda en HELD para reintentar", id);
            return repository.findById(id).orElseThrow();
        }
    }

    // ------------------------------------------------------------------ telefono

    public ReservationResponse submitPhone(Long userId, Long id, String rawPhone) {
        ReservationProcess p = owned(userId, id);
        String phone = PhoneNumbers.normalize(rawPhone)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE",
                "El telefono debe tener entre 7 y 15 digitos, opcionalmente con +"));
        if (p.getStatus() == ReservationStatus.PHONE_SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "PHONE_ALREADY_SUBMITTED",
                "El telefono ya fue enviado; espere el resultado");
        }
        if (!p.getStatus().acceptsPhone()) {
            throw new ApiException(HttpStatus.CONFLICT, "PHONE_NOT_REQUESTED",
                "La reserva no esta esperando un telefono (estado " + p.getStatus() + ")");
        }
        if (p.isExpiredAt(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_EXPIRED", "El tiempo para completar la reserva vencio");
        }
        try {
            phonePublisher.publish(p, phone);
        } catch (MessagingUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MESSAGING_UNAVAILABLE",
                "No se pudo enviar el telefono. Intente nuevamente.");
        }
        // Si el resultado llego por Kafka antes de esta linea, phoneSubmitted no aplica y el estado se respeta.
        return toResponse(store.update(id, x -> x.phoneSubmitted(phone, clock.instant())));
    }

    // ------------------------------------------------------------------ cancelacion

    public ReservationResponse cancel(Long userId, Long id, String reason) {
        ReservationProcess p = owned(userId, id);
        if (p.getStatus() == ReservationStatus.CANCELLED) {
            return toResponse(p); // idempotente
        }
        if (p.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_NOT_CANCELLABLE",
                "Solo se pueden cancelar reservas confirmadas");
        }
        LocalDateTime nowLocal = LocalDateTime.now(clock.withZone(properties.zone()));
        if (!LocalDateTime.of(p.getAppointmentDate(), p.getStartTime()).isAfter(nowLocal)) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_IN_PAST", "El turno ya paso");
        }
        try {
            CancelResponse r = catedra.cancel(p.getReservationProcessId(), reason);
            return toResponse(store.update(id, x -> x.cancelled(reason, r.cancelledAt(), clock.instant())));
        } catch (CatedraApiException e) {
            if (e.is("RESERVATION_NOT_CANCELLABLE") || e.is("RESERVATION_NOT_FOUND")) {
                // El estado de la catedra no coincide con el local: se reconcilia y se informa.
                reconciler.reconcileUser(p.getExternalPatientId());
            }
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_NOT_CANCELLABLE",
                "La catedra no permitio cancelar la reserva");
        } catch (CatedraUnavailableException e) {
            // La cancelacion es idempotente: el usuario puede reintentar sin riesgo.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CATEDRA_UNAVAILABLE",
                "No se pudo cancelar ahora. Intente nuevamente.");
        }
    }

    // ------------------------------------------------------------------ consultas

    public ReservationResponse get(Long userId, Long id) {
        return toResponse(owned(userId, id));
    }

    /** Solo datos propios: funciona aunque la catedra o el catalogo no esten disponibles. */
    public ReservationPage list(Long userId, List<ReservationStatus> statuses, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), NEWEST_FIRST);
        Page<ReservationProcess> result = statuses == null || statuses.isEmpty()
            ? repository.findByUserId(userId, pageable)
            : repository.findByUserIdAndStatusIn(userId, statuses, pageable);
        return new ReservationPage(result.map(this::toResponse).getContent(), result.getNumber(), result.getSize(),
            result.getTotalElements(), result.getTotalPages());
    }

    /** Reconcilia las reservas del usuario con el listado de la catedra (filtrado por su externalPatientId). */
    public ReservationPage synchronize(Long userId) {
        User user = users.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Usuario inexistente"));
        try {
            reconciler.reconcileUser(user.getExternalId());
        } catch (CatedraUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CATEDRA_UNAVAILABLE",
                "No se pudo consultar a la catedra");
        }
        return list(userId, null, 0, 100);
    }

    private ReservationProcess owned(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "No existe la reserva"));
    }

    private ReservationResponse toResponse(ReservationProcess p) {
        return ReservationResponse.from(p, clock.instant());
    }

    private static String patientName(String requested, String fromAccount, String field) {
        String value = requested != null && !requested.isBlank() ? requested.trim()
            : fromAccount == null ? null : fromAccount.trim();
        if (value == null || value.length() < 2 || value.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATIENT_NAME_REQUIRED",
                field + " es obligatorio (2 a 100 caracteres) si la cuenta no lo tiene");
        }
        return value;
    }

    private static ApiException holdError(CatedraApiException e) {
        String code = e.getCode() == null ? "" : e.getCode();
        return switch (code) {
            case "SLOT_ALREADY_HELD", "SLOT_ALREADY_RESERVED" ->
                new ApiException(HttpStatus.CONFLICT, "SLOT_NOT_AVAILABLE", "El turno ya no esta disponible");
            case "INVALID_SLOT" ->
                new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SLOT", "El turno no es valido para la catedra");
            case "PROFESSIONAL_DISABLED", "PROFESSIONAL_NOT_FOUND" ->
                new ApiException(HttpStatus.CONFLICT, "PROFESSIONAL_NOT_AVAILABLE", "El profesional no esta disponible");
            default -> new ApiException(HttpStatus.BAD_GATEWAY, "CATEDRA_REJECTED",
                "La catedra rechazo el pedido (" + (code.isEmpty() ? "HTTP " + e.getStatus() : code) + ")");
        };
    }
}
