package dev.ledgerbank.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

class ApiException extends RuntimeException {
    final int status; final String code;
    ApiException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException error, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.getMessage());
        problem.setType(URI.create("https://ledgerbank.test/problems/" + error.code));
        problem.setTitle(error.code); problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("validation_error"); return problem;
    }
}
