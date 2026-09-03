package ehms.boot;

import ehms.security.LoginGuard;
import ehms.util.Json;
import ehms.util.Log;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to the exact JSON shapes + status codes serveApi used. */
public class AuthException extends RuntimeException {
    public AuthException(String message) { super(message); }
}

@RestControllerAdvice
class GlobalExceptionHandler {

    private static ResponseEntity<Object> json(HttpStatus status, String error) {
        return ResponseEntity.status(status).body(Json.obj("ok", false, "error", error));
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Object> auth(AuthException e) { return json(HttpStatus.UNAUTHORIZED, e.getMessage()); }

    @ExceptionHandler(LoginGuard.LockedException.class)
    ResponseEntity<Object> locked(LoginGuard.LockedException e) { return json(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Object> tooBig(MaxUploadSizeExceededException e) {
        return json(HttpStatus.BAD_REQUEST, "Upload too large - files are limited to 5 MB.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> bad(IllegalArgumentException e) { return json(HttpStatus.BAD_REQUEST, e.getMessage()); }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Object> notFound(NoResourceFoundException e, jakarta.servlet.http.HttpServletRequest req) {
        if (req.getRequestURI().startsWith("/api/"))
            return json(HttpStatus.BAD_REQUEST, "Unknown endpoint: " + req.getRequestURI());
        return json(HttpStatus.NOT_FOUND, "Not found.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        Log.error("Unhandled error while serving an API request", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error: " + e);
    }
}