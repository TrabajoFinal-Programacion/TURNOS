package ar.edu.um.turnos.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.turnos.IntegrationTest;
import ar.edu.um.turnos.catalogo.CatalogoUnavailableException;
import ar.edu.um.turnos.catalogo.ProfessionalAgenda;
import ar.edu.um.turnos.catalogo.ProfessionalAgenda.CategoryRef;
import ar.edu.um.turnos.catalogo.ProfessionalAgenda.Schedule;
import ar.edu.um.turnos.catedra.CatedraApiException;
import ar.edu.um.turnos.catedra.CatedraUnavailableException;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.Appointment;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.CancelResponse;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.ConfirmHoldResponse;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.HoldResponse;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.Occupancy;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.OccupancyResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Proceso de reserva completo contra MySQL y Kafka reales. La API REST de la catedra y el catalogo
 * son mocks; los eventos de la catedra se publican de verdad en el topic de acciones y el telefono
 * se lee del topic de telefono.
 */
class ReservationFlowIntegrationTest extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
    /** Un lunes dentro del horizonte de reservas y siempre en el futuro. */
    private static final LocalDate MONDAY =
        LocalDate.now(ZONE).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ReservationProcessRepository repository;
    @Autowired ProcessedEventRepository processedEvents;
    @Autowired ReservationRecovery recovery;
    @Autowired JdbcTemplate jdbc;

    String token;
    String processId;
    String holdId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("DELETE FROM reservation_process");
        jdbc.execute("DELETE FROM processed_event");
        token = newUserToken();
        processId = "process-" + UUID.randomUUID();
        holdId = "hold-" + UUID.randomUUID();
        when(catalogoClient.professional(15L)).thenReturn(Optional.of(agenda(true)));
        when(catedraClient.createHold(any())).thenReturn(
            new HoldResponse(holdId, processId, "HELD", Instant.now().plusSeconds(600)));
        when(catedraClient.confirmHold(eq(holdId), any())).thenReturn(
            new ConfirmHoldResponse(processId, "WAITING_FOR_PHONE", Instant.now().plusSeconds(600)));
    }

    // ------------------------------------------------------------------ evidencia: reserva confirmada

    @Test
    void reservationIsConfirmedThroughRestAndKafka() throws Exception {
        long id = startReservation();

        sendEvent(requested("req-" + processId));
        awaitStatus(id, "PHONE_REQUESTED");
        mvc.perform(get("/api/reservations/" + id).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(jsonPath("$.message").value("Ingrese un numero de telefono para continuar."))
            .andExpect(jsonPath("$.canSubmitPhone").value(true));

        submitPhone(id, "+54 9 261 555-1234")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PHONE_SUBMITTED"));

        ConsumerRecord<String, String> sent = readPhoneMessage(processId);
        JsonNode phoneEvent = objectMapper.readTree(sent.value());
        assertThat(sent.key()).isEqualTo(processId);
        assertThat(phoneEvent.get("eventType").asText()).isEqualTo("AdditionalInformationSubmitted");
        assertThat(phoneEvent.get("groupId").asText()).isEqualTo("test-group");
        assertThat(phoneEvent.get("reservationProcessId").asText()).isEqualTo(processId);
        assertThat(phoneEvent.get("holdId").asText()).isEqualTo(holdId);
        assertThat(phoneEvent.get("requestEventId").asText()).isEqualTo("req-" + processId);
        assertThat(phoneEvent.get("information").get("phoneNumber").asText()).isEqualTo("+5492615551234");
        assertThat(phoneEvent.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(phoneEvent.get("eventId").asText()).isNotBlank();

        sendEvent(confirmed("conf-" + processId, 8251L));
        awaitStatus(id, "CONFIRMED");
        mvc.perform(get("/api/reservations").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(jsonPath("$.content[0].id").value(id))
            .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.content[0].reservationId").value(8251))
            .andExpect(jsonPath("$.content[0].canCancel").value(true));
    }

    // ------------------------------------------------------------------ evidencia: reserva que expira

    @Test
    void reservationExpiresWithoutPhoneAndLateEventsDoNotReopenIt() throws Exception {
        long id = startReservation();
        sendEvent(requested("req-" + processId));
        awaitStatus(id, "PHONE_REQUESTED");

        sendEvent(event("AppointmentProcessExpired", "exp-" + processId, Map.of("reason", "HOLD_EXPIRED")));
        awaitStatus(id, "EXPIRED");

        // Evento tardio: no reabre el proceso.
        sendEvent(requested("req-tardio-" + processId));
        await().atMost(TIMEOUT).until(() -> processedEvents.existsById("req-tardio-" + processId));
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.EXPIRED);

        submitPhone(id, "+5492615551234")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PHONE_NOT_REQUESTED"));
    }

    @Test
    void rejectedPhoneCanBeSentAgain() throws Exception {
        long id = startReservation();
        sendEvent(requested("req-" + processId));
        awaitStatus(id, "PHONE_REQUESTED");
        submitPhone(id, "+5492615551234").andExpect(status().isOk());

        sendEvent(event("AdditionalInformationRejected", "rej-" + processId, Map.of(
            "reason", "INVALID_PHONE_NUMBER", "message", "El telefono ingresado no es valido.",
            "requestEventId", "req-" + processId)));
        awaitStatus(id, "PHONE_REJECTED");
        mvc.perform(get("/api/reservations/" + id).header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(jsonPath("$.message").value("El telefono ingresado no es valido."))
            .andExpect(jsonPath("$.canSubmitPhone").value(true));

        submitPhone(id, "+5492615559999")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PHONE_SUBMITTED"));
    }

    @Test
    void invalidPhoneIsRejectedBeforeSendingIt() throws Exception {
        long id = startReservation();
        sendEvent(requested("req-" + processId));
        awaitStatus(id, "PHONE_REQUESTED");

        submitPhone(id, "abc")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_PHONE"));
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.PHONE_REQUESTED);
    }

    // ------------------------------------------------------------------ evidencia: duplicados

    @Test
    void duplicatedEventIsProcessedOnlyOnce() throws Exception {
        long id = startReservation();
        String requestId = "req-" + processId;
        sendEvent(requested(requestId));
        awaitStatus(id, "PHONE_REQUESTED");

        sendEvent(requested(requestId)); // mismo eventId

        await().atMost(TIMEOUT).until(() ->
            processedEvents.findById(requestId).map(ProcessedEvent::getDuplicateCount).orElse(0) == 1);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.PHONE_REQUESTED);
    }

    // ------------------------------------------------------------------ evidencia: consulta y cancelacion

    @Test
    void userCancelsOwnConfirmedReservationAndRepeatingIsIdempotent() throws Exception {
        long id = confirmedReservation();
        when(catedraClient.cancel(eq(processId), eq("Cambio de planes")))
            .thenReturn(new CancelResponse(8251L, processId, "CANCELLED", Instant.now()));

        cancel(id, "Cambio de planes")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellationReason").value("Cambio de planes"));
        cancel(id, "Cambio de planes")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(catedraClient, times(1)).cancel(any(), any());
    }

    @Test
    void usersCannotSeeOrModifyReservationsOfOtherUsers() throws Exception {
        long id = confirmedReservation();
        String otherUser = newUserToken();

        mvc.perform(get("/api/reservations/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherUser))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mvc.perform(post("/api/reservations/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherUser))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/reservations/" + id + "/phone").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherUser)
                .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+5492615551234\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/reservations").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherUser))
            .andExpect(jsonPath("$.totalElements").value(0));
        verify(catedraClient, never()).cancel(any(), any());
    }

    @Test
    void reservationsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/reservations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyConfirmedReservationsCanBeCancelled() throws Exception {
        long id = startReservation();
        cancel(id, null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"));
    }

    // ------------------------------------------------------------------ validaciones y errores

    @Test
    void takenSlotIsReportedAndTheProcessIsClosed() throws Exception {
        when(catedraClient.createHold(any())).thenThrow(new CatedraApiException(409, "SLOT_ALREADY_HELD", "held"));

        reserve("09:00", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SLOT_NOT_AVAILABLE"));

        assertThat(repository.findAll()).singleElement()
            .satisfies(p -> {
                assertThat(p.getStatus()).isEqualTo(ReservationStatus.FAILED);
                assertThat(p.getFailureReason()).isEqualTo("SLOT_ALREADY_HELD");
            });
    }

    @Test
    void withoutCatalogNoNewReservationIsStarted() throws Exception {
        when(catalogoClient.professional(15L)).thenThrow(new CatalogoUnavailableException("caido", null));

        reserve("09:00", null)
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));

        verify(catedraClient, never()).createHold(any());
        assertThat(repository.count()).isZero();
    }

    @Test
    void myReservationsAreAvailableEvenIfCatalogIsDown() throws Exception {
        startReservation();
        when(catalogoClient.professional(anyLong())).thenThrow(new CatalogoUnavailableException("caido", null));

        mvc.perform(get("/api/reservations").header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void slotOutsideTheCurrentAgendaIsRejected() throws Exception {
        reserve("09:15", null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_SLOT"));
        verify(catedraClient, never()).createHold(any());
    }

    @Test
    void disabledProfessionalCannotBeBooked() throws Exception {
        when(catalogoClient.professional(15L)).thenReturn(Optional.of(agenda(false)));
        reserve("09:00", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_AVAILABLE"));
    }

    /** Repeticion por timeout: la app reenvia con la misma Idempotency-Key y no se crea otro hold. */
    @Test
    void repeatedRequestWithSameIdempotencyKeyReturnsTheSameProcess() throws Exception {
        String key = UUID.randomUUID().toString();
        long first = id(reserve("09:00", key).andExpect(status().isCreated()));
        long second = id(reserve("09:00", key).andExpect(status().isCreated()));

        assertThat(second).isEqualTo(first);
        verify(catedraClient, times(1)).createHold(any());
    }

    // ------------------------------------------------------------------ recuperacion

    @Test
    void confirmationWithoutResponseIsRetriedByRecovery() throws Exception {
        when(catedraClient.confirmHold(eq(holdId), any()))
            .thenThrow(new CatedraUnavailableException("timeout", null))
            .thenReturn(new ConfirmHoldResponse(processId, "WAITING_FOR_PHONE", Instant.now().plusSeconds(600)));

        long id = id(reserve("09:00", null).andExpect(jsonPath("$.status").value("HELD")));

        recovery.recoverPending();

        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
    }

    @Test
    void lostConfirmationEventIsRecoveredFromTheCatedraListing() throws Exception {
        long id = startReservation();
        sendEvent(requested("req-" + processId));
        awaitStatus(id, "PHONE_REQUESTED");
        submitPhone(id, "+5492615551234").andExpect(status().isOk());
        String externalId = repository.findById(id).orElseThrow().getExternalPatientId();
        // AppointmentConfirmed nunca llega por Kafka; la catedra si la tiene confirmada.
        when(catedraClient.appointmentsOf(externalId)).thenReturn(List.of(appointment(externalId, "CONFIRMED")));

        recovery.recoverPending();

        ReservationProcess p = repository.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(p.getReservationId()).isEqualTo(8251L);
    }

    // ------------------------------------------------------------------ disponibilidad

    @Test
    void availabilityCombinesAgendaAndOccupancies() throws Exception {
        when(catedraClient.occupancies(15L, MONDAY, MONDAY)).thenReturn(new OccupancyResponse(15L, MONDAY, MONDAY, List.of(
            new Occupancy(MONDAY, LocalTime.of(9, 0), "CONFIRMED", null),
            new Occupancy(MONDAY, LocalTime.of(9, 30), "HELD", Instant.now().plusSeconds(300)))));

        mvc.perform(get("/api/availability").param("professionalId", "15").param("date", MONDAY.toString())
                .header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bookable").value(true))
            .andExpect(jsonPath("$.slots.length()").value(6))
            .andExpect(jsonPath("$.slots[0].startTime").value("09:00"))
            .andExpect(jsonPath("$.slots[0].status").value("RESERVED"))
            .andExpect(jsonPath("$.slots[1].status").value("HELD"))
            .andExpect(jsonPath("$.slots[2].status").value("AVAILABLE"));
    }

    @Test
    void availabilityForPastDateIsRejected() throws Exception {
        mvc.perform(get("/api/availability").param("professionalId", "15")
                .param("date", LocalDate.now(ZONE).minusDays(1).toString())
                .header(HttpHeaders.AUTHORIZATION, bearer()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("DATE_IN_PAST"));
    }

    // ------------------------------------------------------------------ helpers

    private static ProfessionalAgenda agenda(boolean enabled) {
        return new ProfessionalAgenda(15L, "Ana", "Gomez", enabled, 1L, new CategoryRef(1L, "Clinica medica", true),
            List.of(new Schedule(21L, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, true)), 7);
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private ResultActions reserve(String startTime, String idempotencyKey) throws Exception {
        var request = post("/api/reservations")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"professionalId":15,"date":"%s","startTime":"%s","patientFirstName":"Juan","patientLastName":"Perez"}
                """.formatted(MONDAY, startTime));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private long startReservation() throws Exception {
        return id(reserve("09:00", null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE")));
    }

    private long confirmedReservation() throws Exception {
        long id = startReservation();
        sendEvent(requested("req-" + processId));
        awaitStatus(id, "PHONE_REQUESTED");
        submitPhone(id, "+5492615551234").andExpect(status().isOk());
        sendEvent(confirmed("conf-" + processId, 8251L));
        awaitStatus(id, "CONFIRMED");
        return id;
    }

    private long id(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private ResultActions submitPhone(long id, String phone) throws Exception {
        return mvc.perform(post("/api/reservations/" + id + "/phone")
            .header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("phoneNumber", phone))));
    }

    private ResultActions cancel(long id, String reason) throws Exception {
        var request = post("/api/reservations/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer());
        if (reason != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", reason)));
        }
        return mvc.perform(request);
    }

    private void awaitStatus(long id, String expected) {
        await().atMost(TIMEOUT).until(() ->
            repository.findById(id).map(p -> p.getStatus().name().equals(expected)).orElse(false));
    }

    private String requested(String eventId) throws Exception {
        return event("AdditionalInformationRequested", eventId, Map.of(
            "requiredField", "PHONE_NUMBER",
            "message", "Ingrese un numero de telefono para continuar.",
            "expiresAt", Instant.now().plusSeconds(600).toString()));
    }

    private String confirmed(String eventId, long reservationId) throws Exception {
        return event("AppointmentConfirmed", eventId, Map.of(
            "reservationId", reservationId, "professionalId", 15, "date", MONDAY.toString(),
            "startTime", "09:00", "status", "CONFIRMED", "requestEventId", "req-" + processId));
    }

    private String event(String type, String eventId, Map<String, Object> extra) throws Exception {
        var node = objectMapper.createObjectNode()
            .put("eventId", eventId)
            .put("eventType", type)
            .put("groupId", "test-group")
            .put("reservationProcessId", processId)
            .put("holdId", holdId)
            .put("occurredAt", Instant.now().toString())
            .put("schemaVersion", 1)
            .put("campoNuevoDesconocido", "se ignora");
        extra.forEach((k, v) -> node.set(k, objectMapper.valueToTree(v)));
        return objectMapper.writeValueAsString(node);
    }

    private void sendEvent(String json) throws Exception {
        kafka.send(ACTIONS_TOPIC, processId, json).get();
    }

    private Appointment appointment(String externalId, String status) {
        return new Appointment(8251L, processId, externalId, MONDAY, LocalTime.of(9, 0), LocalTime.of(9, 30), 15L,
            "Ana", "Gomez", "Juan", "Perez", "+5492615551234", status, Instant.now(), Instant.now(), null, null);
    }

    /** Lee del topic de telefono el mensaje cuya key es el proceso indicado. */
    private ConsumerRecord<String, String> readPhoneMessage(String key) {
        Map<String, Object> config = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            TopicPartition partition = new TopicPartition(PHONE_TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No se publico el telefono del proceso " + key);
    }
}
