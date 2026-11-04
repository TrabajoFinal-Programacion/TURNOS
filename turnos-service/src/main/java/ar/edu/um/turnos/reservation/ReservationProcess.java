package ar.edu.um.turnos.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Proceso de reserva de un usuario final. Encapsula la maquina de estados (ver ReservationStatus):
 * cada metodo de transicion valida el estado de origen y devuelve false si la transicion no aplica
 * (evento tardio, duplicado o fuera de orden), sin modificar nada.
 *
 * version (@Version) evita que dos hilos (por ejemplo el request HTTP y el consumidor Kafka)
 * pisen cambios del otro: el segundo en guardar falla y se reintenta sobre el estado nuevo.
 */
@Entity
@Table(name = "reservation_process")
public class ReservationProcess {

    private static final Set<ReservationStatus> BEFORE_PHONE =
        EnumSet.of(ReservationStatus.CREATED, ReservationStatus.HELD, ReservationStatus.WAITING_FOR_PHONE);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "external_patient_id", nullable = false, updatable = false, length = 100)
    private String externalPatientId;

    @Column(name = "client_request_id", updatable = false, length = 100)
    private String clientRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationStatus status;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private Long professionalId;

    @Column(name = "professional_first_name")
    private String professionalFirstName;

    @Column(name = "professional_last_name")
    private String professionalLastName;

    @Column(name = "category_name")
    private String categoryName;

    @Column(name = "appointment_date", nullable = false, updatable = false)
    private LocalDate appointmentDate;

    @Column(name = "start_time", nullable = false, updatable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false, updatable = false)
    private LocalTime endTime;

    @Column(name = "patient_first_name", nullable = false, length = 100)
    private String patientFirstName;

    @Column(name = "patient_last_name", nullable = false, length = 100)
    private String patientLastName;

    @Column(name = "patient_phone", length = 20)
    private String patientPhone;

    @Column(name = "hold_id", length = 100)
    private String holdId;

    @Column(name = "reservation_process_id", length = 100)
    private String reservationProcessId;

    @Column(name = "request_event_id", length = 200)
    private String requestEventId;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "status_message", length = 500)
    private String statusMessage;

    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    private long version;

    protected ReservationProcess() {}

    public static ReservationProcess create(Long userId, String externalPatientId, String clientRequestId,
                                            Long professionalId, String professionalFirstName,
                                            String professionalLastName, String categoryName,
                                            LocalDate date, LocalTime start, LocalTime end,
                                            String patientFirstName, String patientLastName, Instant now) {
        ReservationProcess p = new ReservationProcess();
        p.userId = userId;
        p.externalPatientId = externalPatientId;
        p.clientRequestId = clientRequestId;
        p.status = ReservationStatus.CREATED;
        p.professionalId = professionalId;
        p.professionalFirstName = professionalFirstName;
        p.professionalLastName = professionalLastName;
        p.categoryName = categoryName;
        p.appointmentDate = date;
        p.startTime = start;
        p.endTime = end;
        p.patientFirstName = patientFirstName;
        p.patientLastName = patientLastName;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    // ------------------------------------------------------------------ transiciones

    /** Respuesta 201 de POST /appointment-holds. */
    public boolean holdCreated(String holdId, String reservationProcessId, Instant expiresAt, Instant now) {
        if (status != ReservationStatus.CREATED) {
            return false;
        }
        this.holdId = holdId;
        this.reservationProcessId = reservationProcessId;
        this.expiresAt = expiresAt;
        return moveTo(ReservationStatus.HELD, now);
    }

    /**
     * Respuesta 202 de la confirmacion inicial (o 409 RESERVATION_PROCESS_ALREADY_CONFIRMED en un reintento).
     * Si AdditionalInformationRequested llego por Kafka ANTES que esta respuesta, el proceso ya esta
     * en PHONE_REQUESTED y no retrocede: solo se actualiza el vencimiento si faltaba.
     */
    public boolean confirmAccepted(Instant expiresAt, Instant now) {
        if (expiresAt != null && this.expiresAt == null) {
            this.expiresAt = expiresAt;
        }
        if (status != ReservationStatus.CREATED && status != ReservationStatus.HELD) {
            return false;
        }
        if (expiresAt != null) {
            this.expiresAt = expiresAt;
        }
        return moveTo(ReservationStatus.WAITING_FOR_PHONE, now);
    }

    /** AdditionalInformationRequested. */
    public boolean phoneRequested(String requestEventId, String message, Instant expiresAt, Instant now) {
        if (!BEFORE_PHONE.contains(status) && status != ReservationStatus.PHONE_REQUESTED) {
            return false;
        }
        this.requestEventId = requestEventId;
        this.statusMessage = truncate(message, 500);
        if (expiresAt != null) {
            this.expiresAt = expiresAt;
        }
        return moveTo(ReservationStatus.PHONE_REQUESTED, now);
    }

    /** El usuario informo el telefono y se publico AdditionalInformationSubmitted. */
    public boolean phoneSubmitted(String phone, Instant now) {
        if (!status.acceptsPhone()) {
            return false;
        }
        this.patientPhone = phone;
        this.statusMessage = null;
        return moveTo(ReservationStatus.PHONE_SUBMITTED, now);
    }

    /** AdditionalInformationRejected: el proceso sigue abierto hasta expiresAt. */
    public boolean phoneRejected(String reason, String message, Instant expiresAt, Instant now) {
        if (status != ReservationStatus.PHONE_SUBMITTED && status != ReservationStatus.PHONE_REQUESTED) {
            return false;
        }
        this.failureReason = truncate(reason, 100);
        this.statusMessage = truncate(message, 500);
        if (expiresAt != null) {
            this.expiresAt = expiresAt;
        }
        return moveTo(ReservationStatus.PHONE_REJECTED, now);
    }

    /** AppointmentConfirmed, o reconciliacion REST que encontro la reserva CONFIRMED. */
    public boolean confirmed(Long reservationId, Instant confirmedAt, Instant now) {
        if (!status.isPending()) {
            return false; // ya confirmada (duplicado) o en estado final: no se reabre
        }
        this.reservationId = reservationId;
        this.confirmedAt = confirmedAt != null ? confirmedAt : now;
        this.failureReason = null;
        this.statusMessage = null;
        return moveTo(ReservationStatus.CONFIRMED, now);
    }

    /** AppointmentProcessExpired, HOLD_EXPIRED por REST o vencimiento verificado en la reconciliacion. */
    public boolean expired(String reason, Instant now) {
        return closeAs(ReservationStatus.EXPIRED, reason, now);
    }

    /** AppointmentProcessInvalid. */
    public boolean invalid(String reason, Instant now) {
        return closeAs(ReservationStatus.INVALID, reason, now);
    }

    /** No se pudo obtener el hold o confirmar (turno tomado, slot invalido, respuesta desconocida). */
    public boolean failed(String reason, String message, Instant now) {
        if (closeAs(ReservationStatus.FAILED, reason, now)) {
            this.statusMessage = truncate(message, 500);
            return true;
        }
        return false;
    }

    /** Cancelacion confirmada por la catedra (respuesta REST o AppointmentCancelled). */
    public boolean cancelled(String reason, Instant cancelledAt, Instant now) {
        if (status != ReservationStatus.CONFIRMED) {
            return false;
        }
        this.cancellationReason = truncate(reason, 500);
        this.cancelledAt = cancelledAt != null ? cancelledAt : now;
        return moveTo(ReservationStatus.CANCELLED, now);
    }

    private boolean closeAs(ReservationStatus target, String reason, Instant now) {
        if (!status.isPending()) {
            return false; // CONFIRMED o final: un vencimiento/invalidez tardio no lo cambia
        }
        this.failureReason = truncate(reason, 100);
        return moveTo(target, now);
    }

    private boolean moveTo(ReservationStatus target, Instant now) {
        this.status = target;
        this.updatedAt = now;
        return true;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    // ------------------------------------------------------------------ consultas

    public boolean belongsTo(Long userId) {
        return this.userId.equals(userId);
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getExternalPatientId() { return externalPatientId; }
    public String getClientRequestId() { return clientRequestId; }
    public ReservationStatus getStatus() { return status; }
    public Long getProfessionalId() { return professionalId; }
    public String getProfessionalFirstName() { return professionalFirstName; }
    public String getProfessionalLastName() { return professionalLastName; }
    public String getCategoryName() { return categoryName; }
    public LocalDate getAppointmentDate() { return appointmentDate; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public String getPatientFirstName() { return patientFirstName; }
    public String getPatientLastName() { return patientLastName; }
    public String getPatientPhone() { return patientPhone; }
    public String getHoldId() { return holdId; }
    public String getReservationProcessId() { return reservationProcessId; }
    public String getRequestEventId() { return requestEventId; }
    public Long getReservationId() { return reservationId; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getStatusMessage() { return statusMessage; }
    public String getFailureReason() { return failureReason; }
    public String getCancellationReason() { return cancellationReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public long getVersion() { return version; }
}
