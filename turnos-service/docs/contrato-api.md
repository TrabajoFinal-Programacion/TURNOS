# Contrato de la API de turnos (para la app KMP)

Base: `http://<host>:8082`. Errores: `application/problem+json` con un `code` estable.

## Autenticación

| Método | Ruta | Body | Respuesta |
| --- | --- | --- | --- |
| `POST` | `/api/register` | `login, password, firstName, lastName, email, imageUrl, langKey` | `201` |
| `POST` | `/api/authenticate` | `username, password, rememberMe` | `200 {"id_token": "..."}` |
| `GET` | `/api/account` | — | datos de la cuenta |

El resto de las rutas exige `Authorization: Bearer <id_token>`.

## Disponibilidad

`GET /api/availability?professionalId=15&date=2026-11-09`

```json
{
  "professionalId": 15, "professionalFirstName": "Ana", "professionalLastName": "Gomez",
  "categoryName": "Clinica medica", "date": "2026-11-09", "bookable": true, "catalogVersion": 7,
  "slots": [
    { "date": "2026-11-09", "startTime": "09:00", "endTime": "09:30", "status": "RESERVED" },
    { "date": "2026-11-09", "startTime": "09:30", "endTime": "10:00", "status": "AVAILABLE" }
  ]
}
```

`status`: `AVAILABLE` (se puede reservar), `HELD`, `RESERVED`, `PAST`.

## Iniciar una reserva

`POST /api/reservations`. Header opcional `Idempotency-Key: <uuid>`: si la app repite el pedido (por ejemplo, después de un timeout), recibe el mismo proceso.

```json
{ "professionalId": 15, "date": "2026-11-09", "startTime": "09:30",
  "patientFirstName": "Juan", "patientLastName": "Perez" }
```

`patientFirstName` y `patientLastName` son opcionales: si faltan, se usan los de la cuenta.

`201`:

```json
{
  "id": 42, "status": "WAITING_FOR_PHONE",
  "professionalId": 15, "professionalFirstName": "Ana", "professionalLastName": "Gomez",
  "categoryName": "Clinica medica", "date": "2026-11-09", "startTime": "09:30", "endTime": "10:00",
  "patientFirstName": "Juan", "patientLastName": "Perez", "patientPhone": null,
  "expiresAt": "2026-11-01T14:44:50Z", "message": null, "failureReason": null, "reservationId": null,
  "createdAt": "...", "confirmedAt": null, "cancelledAt": null, "cancellationReason": null,
  "canSubmitPhone": false, "canCancel": false
}
```

Después, la app consulta `GET /api/reservations/42` hasta que `canSubmitPhone` sea `true` (estado `PHONE_REQUESTED`). En ese momento muestra `message` y pide el teléfono.

## Enviar el teléfono

`POST /api/reservations/{id}/phone` con `{ "phoneNumber": "+54 9 261 555-1234" }` → `200`, estado `PHONE_SUBMITTED`.

La app vuelve a consultar hasta un estado de resultado:

| Estado | Qué mostrar |
| --- | --- |
| `CONFIRMED` | reserva confirmada (`reservationId`) |
| `PHONE_REJECTED` | `message` y volver a pedir el teléfono (`canSubmitPhone: true`) |
| `EXPIRED` | se venció el tiempo |
| `INVALID`, `FAILED` | no se pudo completar (`failureReason`) |

## Mis reservas y cancelación

| Método | Ruta | Descripción |
| --- | --- | --- |
| `GET` | `/api/reservations?status=CONFIRMED&status=CANCELLED&page=0&size=20` | `{content, page, size, totalElements, totalPages}`, más recientes primero |
| `GET` | `/api/reservations/{id}` | una reserva propia |
| `POST` | `/api/reservations/{id}/cancel` | body opcional `{ "reason": "..." }` (máx. 500). Repetirla devuelve el mismo resultado |
| `POST` | `/api/reservations/sync` | reconcilia con la cátedra y devuelve la lista |

## Errores

| HTTP | `code` | Cuándo |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | body o parámetros inválidos (`fieldErrors`) |
| 400 | `DATE_IN_PAST` / `DATE_TOO_FAR` | fecha fuera de rango (máx. 90 días) |
| 400 | `INVALID_SLOT` | el horario no está en la agenda vigente |
| 400 | `SLOT_IN_PAST` | el horario ya pasó |
| 400 | `PATIENT_NAME_REQUIRED` | falta nombre o apellido y la cuenta no los tiene |
| 400 | `INVALID_PHONE` | teléfono con formato inválido (7 a 15 dígitos, `+` opcional) |
| 400 | `INVALID_IDEMPOTENCY_KEY` | `Idempotency-Key` vacía o con más de 100 caracteres |
| 401 | `UNAUTHORIZED` | sin token, o token inválido |
| 404 | `PROFESSIONAL_NOT_FOUND` | el profesional no existe |
| 404 | `RESERVATION_NOT_FOUND` | no existe o **no es tuya** |
| 409 | `SLOT_NOT_AVAILABLE` | el turno fue tomado por otro |
| 409 | `PROFESSIONAL_NOT_AVAILABLE` | profesional o categoría deshabilitados |
| 409 | `PHONE_NOT_REQUESTED` / `PHONE_ALREADY_SUBMITTED` | el estado no admite el teléfono |
| 409 | `RESERVATION_EXPIRED` | venció el plazo para completar la reserva |
| 409 | `RESERVATION_NOT_CANCELLABLE` / `RESERVATION_IN_PAST` | no se puede cancelar |
| 502 | `CATEDRA_REJECTED` | la cátedra rechazó el pedido por un motivo no previsto |
| 503 | `CATALOG_UNAVAILABLE` | catálogo caído: no se inicia ninguna operación nueva |
| 503 | `CATEDRA_UNAVAILABLE` | la cátedra no respondió |
| 503 | `MESSAGING_UNAVAILABLE` | Kafka no confirmó el envío del teléfono; se puede reintentar |
