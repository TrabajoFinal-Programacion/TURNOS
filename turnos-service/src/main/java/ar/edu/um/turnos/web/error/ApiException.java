package ar.edu.um.turnos.web.error;

import org.springframework.http.HttpStatus;

/**
 * Error funcional con un code estable. Los clientes (la app KMP) deciden por status + code,
 * nunca por el texto de detail (misma convencion que el contrato de la catedra).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
