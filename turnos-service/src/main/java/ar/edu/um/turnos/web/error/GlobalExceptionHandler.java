package ar.edu.um.turnos.web.error;

import ar.edu.um.turnos.catalogo.CatalogoUnavailableException;
import ar.edu.um.turnos.catedra.CatedraApiException;
import ar.edu.um.turnos.catedra.CatedraUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Traduce excepciones a application/problem+json con un code funcional estable. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex, HttpServletRequest request) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage(), request.getRequestURI());
    }

    /** Sin catalogo no se valida informacion vigente: no se inicia la operacion (enunciado 8). */
    @ExceptionHandler(CatalogoUnavailableException.class)
    public ProblemDetail handleCatalogo(CatalogoUnavailableException ex, HttpServletRequest request) {
        log.warn("Catalogo no disponible: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE",
            "El catalogo no esta disponible. Intente nuevamente.", request.getRequestURI());
    }

    @ExceptionHandler(CatedraUnavailableException.class)
    public ProblemDetail handleCatedraUnavailable(CatedraUnavailableException ex, HttpServletRequest request) {
        log.warn("Catedra no disponible: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "CATEDRA_UNAVAILABLE",
            "El servicio de turnos no esta disponible. Intente nuevamente.", request.getRequestURI());
    }

    /** Error funcional de la catedra no traducido por el caso de uso. Se informa el code, nunca el token. */
    @ExceptionHandler(CatedraApiException.class)
    public ProblemDetail handleCatedraApi(CatedraApiException ex, HttpServletRequest request) {
        log.warn("Catedra rechazo un pedido: {}", ex.getMessage());
        ProblemDetail pd = problem(HttpStatus.BAD_GATEWAY, "CATEDRA_REJECTED",
            "La catedra rechazo el pedido", request.getRequestURI());
        pd.setProperty("catedraCode", ex.getCode());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        // Se loguea la causa, pero no se expone al cliente.
        log.error("Error no esperado en {}", request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error interno", request.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of(
                "objectName", fe.getObjectName(),
                "field", fe.getField(),
                "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
            .toList();
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Datos invalidos", path(request));
        body.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /** Resto de errores de Spring MVC (JSON mal formado, metodo no soportado, 404...): se les agrega code. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd && pd.getProperties() == null) {
            pd.setProperty("code", statusCode.value() == 400 ? "VALIDATION_ERROR" : "HTTP_" + statusCode.value());
        }
        return response;
    }

    public static ProblemDetail problem(HttpStatusCode status, String code, String detail, String path) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        if (path != null) {
            pd.setInstance(URI.create(path));
        }
        return pd;
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }
}
