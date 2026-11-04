package ar.edu.um.turnos.catedra;

/**
 * La catedra respondio con un error funcional (4xx) y un code estable del contrato,
 * por ejemplo SLOT_ALREADY_HELD o HOLD_EXPIRED. Se decide siempre por status + code.
 */
public class CatedraApiException extends RuntimeException {

    private final int status;
    private final String code;

    public CatedraApiException(int status, String code, String detail) {
        super("Catedra HTTP " + status + (code == null ? "" : " " + code) + (detail == null ? "" : ": " + detail));
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    /** Puede ser null en rechazos de Spring Security (401/403) segun el contrato. */
    public String getCode() {
        return code;
    }

    public boolean is(String expectedCode) {
        return expectedCode.equals(code);
    }
}
