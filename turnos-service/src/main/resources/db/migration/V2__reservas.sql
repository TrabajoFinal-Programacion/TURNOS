-- Procesos de reserva y reservas de los usuarios finales.
-- Una fila = un intento de reserva iniciado por un usuario. Guarda los identificadores de la
-- catedra (holdId, reservationProcessId, reservationId) para poder reconciliar el estado,
-- y la propiedad (user_id), que la catedra no conoce.

CREATE TABLE reservation_process (
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    user_id                  BIGINT        NOT NULL,
    external_patient_id      VARCHAR(100)  NOT NULL,
    client_request_id        VARCHAR(100)  NULL,
    status                   VARCHAR(30)   NOT NULL,

    professional_id          BIGINT        NOT NULL,
    -- Datos descriptivos historicos: NO se usan como catalogo vigente.
    professional_first_name  VARCHAR(255)  NULL,
    professional_last_name   VARCHAR(255)  NULL,
    category_name            VARCHAR(255)  NULL,
    appointment_date         DATE          NOT NULL,
    start_time               TIME          NOT NULL,
    end_time                 TIME          NOT NULL,

    patient_first_name       VARCHAR(100)  NOT NULL,
    patient_last_name        VARCHAR(100)  NOT NULL,
    patient_phone            VARCHAR(20)   NULL,

    hold_id                  VARCHAR(100)  NULL,
    reservation_process_id   VARCHAR(100)  NULL,
    request_event_id         VARCHAR(200)  NULL,
    reservation_id           BIGINT        NULL,
    expires_at               DATETIME(6)   NULL,

    status_message           VARCHAR(500)  NULL,
    failure_reason           VARCHAR(100)  NULL,
    cancellation_reason      VARCHAR(500)  NULL,

    created_at               DATETIME(6)   NOT NULL,
    updated_at               DATETIME(6)   NOT NULL,
    confirmed_at             DATETIME(6)   NULL,
    cancelled_at             DATETIME(6)   NULL,
    version                  BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_reservation_process PRIMARY KEY (id),
    CONSTRAINT fk_reservation_process_user FOREIGN KEY (user_id) REFERENCES jhi_user (id),
    CONSTRAINT ux_reservation_process_hold UNIQUE (hold_id),
    CONSTRAINT ux_reservation_process_catedra UNIQUE (reservation_process_id),
    -- Idempotency-Key de la app: el mismo pedido repetido no crea otra reserva.
    CONSTRAINT ux_reservation_process_client_request UNIQUE (user_id, client_request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX ix_reservation_process_user ON reservation_process (user_id, appointment_date);
CREATE INDEX ix_reservation_process_status ON reservation_process (status);

-- Eventos Kafka ya procesados (idempotencia por eventId). Se insertan en la MISMA transaccion
-- que aplica el efecto: o quedan los dos, o ninguno.
CREATE TABLE processed_event (
    event_id        VARCHAR(200) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    processed_at    DATETIME(6)  NOT NULL,
    duplicate_count INT          NOT NULL DEFAULT 0,
    last_seen_at    DATETIME(6)  NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
