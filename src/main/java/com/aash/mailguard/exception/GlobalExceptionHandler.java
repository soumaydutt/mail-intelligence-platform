package com.aash.mailguard.exception;

import com.aash.mailguard.jobs.JobStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean jobRoute = path.equals("/api/jobs") || path.startsWith("/api/jobs/");
        HttpStatus status;
        if (jobRoute && ex instanceof JobStore.QueueFull) {
            status = HttpStatus.TOO_MANY_REQUESTS;
        } else if (jobRoute && ex instanceof JobStore.IdempotencyConflict) {
            status = HttpStatus.CONFLICT;
        } else if (jobRoute && ex instanceof ResponseStatusException responseStatus) {
            status = HttpStatus.valueOf(responseStatus.getStatusCode().value());
        } else if (jobRoute && ex instanceof org.springframework.web.ErrorResponse webError) {
            status = HttpStatus.valueOf(webError.getStatusCode().value());
        } else {
            status = ex instanceof MethodArgumentNotValidException || ex instanceof HttpMessageNotReadableException
                    ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.setMessage(switch (status) {
            case BAD_REQUEST -> "Invalid request";
            case UNAUTHORIZED -> "Unauthorized";
            case NOT_FOUND -> "Not found";
            case CONFLICT -> "Conflict";
            case TOO_MANY_REQUESTS -> "Job backlog is full";
            case INTERNAL_SERVER_ERROR -> "Internal server error";
            default -> jobRoute ? "Request failed" : "Internal server error";
        });
        errorResponse.setTimestamp(LocalDateTime.now().toString());
        errorResponse.setStatus(status.value());
        errorResponse.setPath(jobRoute ? "/api/jobs"
                : path.equals("/api/email/validate") || path.equals("/api/email/test") ? path : "/");
        return new ResponseEntity<>(errorResponse, status);
    }
}
