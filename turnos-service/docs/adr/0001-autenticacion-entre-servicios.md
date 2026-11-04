# ADR 0001: Autenticación entre servicios por propagación del JWT del usuario

**Estado:** aceptada (Etapa 1)

## Contexto

El enunciado (sección 9) exige que la comunicación entre los dos servicios use JWT validados (firma, vigencia y permisos). Cada alumno elige si propaga el JWT del usuario o usa un JWT técnico, y tiene que justificar cómo se preservan la identidad, la autorización y la trazabilidad.

Turnos necesita consultar a catálogo antes de iniciar una operación: profesional vigente, agenda semanal. Todas esas llamadas se originan en una acción de un usuario final.

## Decisión

Turnos reenvía a catálogo el mismo `Authorization: Bearer <jwt-usuario>` que recibió de la app.

- El token está firmado con **RS256**. Turnos tiene la clave privada; catálogo solo la pública.
- Los dos servicios validan firma, `exp`/`nbf`, `iss = turnos-service`, `aud = turnos-app` y el rol (`ROLE_USER`).
- El JWT técnico de la cátedra **nunca** sale del backend y nunca se mezcla con el del usuario.

## Consecuencias

**A favor**
- Catálogo sabe qué usuario originó cada consulta, y lo puede registrar en los logs.
- No hace falta una segunda identidad ni otro secreto entre servicios.
- Catálogo no puede emitir tokens: si se compromete, no puede hacerse pasar por un usuario ante turnos.

**En contra / límites**
- Si el token del usuario vence durante una operación larga, la llamada a catálogo falla con 401. Las llamadas son cortas y síncronas, así que en la práctica no pasa.
- Los procesos en segundo plano (recuperación de reservas pendientes al reiniciar) **no tienen token de usuario**. Por diseño, esos procesos no llaman a catálogo: solo reconcilian con la cátedra datos que turnos ya guardó. Si en el futuro hiciera falta, se agregaría un token técnico de servicio con un rol propio (`ROLE_SERVICE`), sin reemplazar la propagación.

## Alternativa descartada

**JWT técnico de turnos hacia catálogo.** Requiere un emisor y un secreto más. Además, catálogo perdería la identidad del usuario, salvo que se agregara a mano en otra cabecera, en la que catálogo tendría que confiar sin poder verificarla.
