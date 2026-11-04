# Proceso de reserva

## Flujo

```
App KMP                 turnos-service                         catalogo-service     Catedra
   │ GET /availability ───▶│── GET /api/professionals/{id} (JWT usuario) ─▶│
   │                       │── GET /api/appointment-occupancies (JWT tecnico) ─────────────▶│
   │◀── turnos del dia ────│
   │ POST /reservations ──▶│── GET /api/professionals/{id} (valida agenda vigente) ─▶│
   │                       │   INSERT reservation_process (CREATED)
   │                       │── POST /api/appointment-holds ─────────────────────────────────▶│
   │                       │   HELD (holdId, reservationProcessId, expiresAt)
   │                       │── POST /api/appointment-holds/{holdId}/confirm ────────────────▶│
   │◀── WAITING_FOR_PHONE ─│   WAITING_FOR_PHONE
   │                       │◀── Kafka AdditionalInformationRequested ─────────────────────────│
   │  (consulta estado)    │   PHONE_REQUESTED (requestEventId = eventId)
   │ POST /{id}/phone ────▶│── Kafka AdditionalInformationSubmitted (key = processId) ──────▶│
   │◀── PHONE_SUBMITTED ───│
   │                       │◀── Kafka AppointmentConfirmed | Rejected | Expired | Invalid ──│
   │  (consulta estado)    │   CONFIRMED | PHONE_REJECTED | EXPIRED | INVALID
```

La app consulta el estado con `GET /api/reservations/{id}`. Los campos `canSubmitPhone` y `canCancel` le dicen qué acciones ofrecer.

## Máquina de estados local

| Estado | Significado | Sale con |
| --- | --- | --- |
| `CREATED` | registro local; todavía no hay respuesta del hold | hold 201 → `HELD`; error → `FAILED` |
| `HELD` | hold obtenido | confirm 202 → `WAITING_FOR_PHONE`; `HOLD_EXPIRED` → `EXPIRED`; `Requested` → `PHONE_REQUESTED` |
| `WAITING_FOR_PHONE` | confirmación inicial aceptada | `Requested` → `PHONE_REQUESTED` |
| `PHONE_REQUESTED` | la cátedra pidió el teléfono | teléfono enviado → `PHONE_SUBMITTED` |
| `PHONE_SUBMITTED` | teléfono publicado en Kafka | `Confirmed` → `CONFIRMED`; `Rejected` → `PHONE_REJECTED` |
| `PHONE_REJECTED` | teléfono rechazado; reintentable hasta `expiresAt` | teléfono enviado → `PHONE_SUBMITTED` |
| `CONFIRMED` | reserva confirmada | cancelación → `CANCELLED` |
| `CANCELLED`, `EXPIRED`, `INVALID`, `FAILED` | **finales** | ninguna |

Reglas (implementadas en `ReservationProcess` y probadas en `ReservationProcessTest`):

- **Un estado final nunca se abandona.** Un evento tardío (por ejemplo, un `Requested` después de un `Expired`) se registra y se ignora.
- **`CONFIRMED` solo pasa a `CANCELLED`.** Un `Expired` o `Invalid` tardío no la cambia.
- **REST y Kafka pueden llegar en cualquier orden.** Si `Requested` llega antes que la respuesta 202 de la confirmación, el proceso ya está en `PHONE_REQUESTED` y la respuesta no lo hace retroceder.
- **Concurrencia:** `@Version` en la entidad. Si el request HTTP y el consumidor Kafka modifican el mismo proceso a la vez, el segundo relee y vuelve a evaluar la transición.

## Idempotencia

| Situación | Solución |
| --- | --- |
| La app repite `POST /reservations` por un timeout | header `Idempotency-Key` (único por usuario): devuelve el mismo proceso sin crear otro hold |
| Evento Kafka duplicado | `processed_event` guarda los `eventId`. El registro y el cambio de estado van en la **misma transacción**, así que un duplicado no repite efectos y solo incrementa `duplicate_count` |
| Confirmación inicial repetida | `RESERVATION_PROCESS_ALREADY_CONFIRMED` se trata como éxito |
| Cancelación repetida | local: si ya está `CANCELLED`, se devuelve sin llamar a la cátedra; cátedra: idempotente por contrato |
| Teléfono reenviado tras un rechazo | `eventId` nuevo en cada envío, con el mismo `requestEventId` |

## Timeouts y reintentos

| Llamada | Timeout | Reintentos |
| --- | --- | --- |
| Catálogo (`GET /professionals/{id}`) | 2 s conexión, 5 s lectura | 2 intentos |
| Cátedra: ocupaciones, listado | 3 s / 10 s | 3, con espera creciente |
| Cátedra: crear hold | 3 s / 10 s | **ninguno** (no se puede consultar; el hold huérfano vence solo) |
| Cátedra: confirmar hold | 3 s / 10 s | 3 en el request; después, la recuperación hasta `expiresAt` |
| Cátedra: cancelar | 3 s / 10 s | 3 (idempotente) |
| Kafka: publicar teléfono | 10 s | ninguno automático; el usuario recibe 503 y puede reintentar |
| Kafka: procesar un evento | — | 3 reintentos cada 2 s; después se confirma el offset y la recuperación reconcilia por REST |

Ningún reintento es ilimitado.

## Recuperación (`ReservationRecovery`)

Corre 10 s después de arrancar y luego cada 30 s, sobre procesos abiertos sin cambios en los últimos 30 s:

| Estado | Acción |
| --- | --- |
| `CREATED` | nunca se supo si el hold se creó, y no se puede consultar → `FAILED (HOLD_UNKNOWN)` |
| `HELD` vigente | reintenta la confirmación inicial |
| resto | reconcilia con `GET /api/appointments?externalPatientId=…` (una consulta por usuario) |

Reconciliación:

| La cátedra dice | Resultado local |
| --- | --- |
| `CONFIRMED` | `CONFIRMED` (recupera un `AppointmentConfirmed` perdido) |
| `CANCELLED` | `CANCELLED` |
| `FAILED` | `EXPIRED (CATEDRA_FAILED)` |
| `PHONE_PENDING` | sigue esperando; si pasó `expiresAt` + 2 min → `EXPIRED (LOCAL_TIMEOUT)` |
| no existe | si pasó `expiresAt` + 2 min → `EXPIRED (NOT_FOUND_AT_CATEDRA)` |

Todo proceso abierto termina a lo sumo en `expiresAt` + 2 minutos. No quedan estados imposibles de recuperar.

`AppointmentProcessInvalid` dispara además una reconciliación inmediata (contrato 15.9).

## Propiedad por usuario

- `reservation_process.user_id` es el dueño. Todas las consultas del usuario usan `findByIdAndUserId`, y un proceso ajeno responde **404** (sin revelar que existe).
- Ante la cátedra, el usuario se identifica con `external_patient_id` (UUID estable de `jhi_user.external_id`), no con el id numérico ni con datos enviados por el cliente.
- El listado de la cátedra devuelve las reservas de **toda** la cuenta técnica. Se filtra por `externalPatientId` y solo se aplica sobre procesos locales del mismo usuario.
