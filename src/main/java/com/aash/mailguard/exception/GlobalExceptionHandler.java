package com.aash.mailguard.exception;

import com.aash.mailguard.jobs.JobStore;
import com.aash.mailguard.delivery.DeliveryEvidenceService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean jobRoute = path.equals("/api/jobs") || path.startsWith("/api/jobs/");
        boolean importRoute = path.equals("/api/imports") || path.startsWith("/api/imports/");
        boolean eventRoute = path.equals("/api/delivery-events") || path.startsWith("/api/delivery-events/");
        boolean evidenceRoute = path.equals("/api/delivery-evidence/lookup");
        boolean suppressionRoute = path.equals("/api/suppressions") || path.startsWith("/api/suppressions/");
        boolean deliveryRoute = eventRoute || evidenceRoute || suppressionRoute;
        boolean validationRoute = path.equals("/api/email/validate");
        boolean protectedRoute = jobRoute || importRoute || deliveryRoute;
        HttpStatus status;
        if (jobRoute && ex instanceof JobStore.QueueFull) {
            status = HttpStatus.TOO_MANY_REQUESTS;
        } else if (jobRoute && ex instanceof JobStore.IdempotencyConflict) {
            status = HttpStatus.CONFLICT;
        } else if (deliveryRoute && ex instanceof DeliveryEvidenceService.InvalidInput) {
            status = HttpStatus.BAD_REQUEST;
        } else if (deliveryRoute && ex instanceof DeliveryEvidenceService.Conflict) {
            status = HttpStatus.CONFLICT;
        } else if (deliveryRoute && ex instanceof DeliveryEvidenceService.NotFound) {
            status = HttpStatus.NOT_FOUND;
        } else if (validationRoute && ex instanceof ResponseStatusException responseStatus
                && responseStatus.getStatusCode().value() == 401) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (deliveryRoute && (ex instanceof IOException
                || ex instanceof org.apache.tomcat.util.http.InvalidParameterException)) {
            status = HttpStatus.BAD_REQUEST;
        } else if (protectedRoute && ex instanceof ResponseStatusException responseStatus) {
            status = HttpStatus.valueOf(responseStatus.getStatusCode().value());
        } else if (protectedRoute && ex instanceof org.springframework.web.ErrorResponse webError) {
            status = HttpStatus.valueOf(webError.getStatusCode().value());
        } else if (importRoute && (ex instanceof MultipartException
                || ex instanceof ServletException || ex instanceof IOException
                || ex instanceof org.apache.tomcat.util.http.InvalidParameterException)) {
            status = HttpStatus.BAD_REQUEST;
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
            case CONTENT_TOO_LARGE, PAYLOAD_TOO_LARGE -> "Request too large";
            case TOO_MANY_REQUESTS -> importRoute ? "Upload limit reached" : "Job backlog is full";
            case INTERNAL_SERVER_ERROR -> "Internal server error";
            default -> protectedRoute ? "Request failed" : "Internal server error";
        });
        errorResponse.setTimestamp(LocalDateTime.now().toString());
        errorResponse.setStatus(status.value());
        errorResponse.setPath(jobRoute ? "/api/jobs" : importRoute ? "/api/imports"
                : eventRoute ? "/api/delivery-events" : evidenceRoute ? "/api/delivery-evidence/lookup"
                : suppressionRoute ? "/api/suppressions"
                : path.equals("/api/email/validate") || path.equals("/api/email/test") ? path : "/");
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.RETRY_AFTER, "1").body(errorResponse);
        }
        return deliveryRoute
                ? ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(errorResponse)
                : new ResponseEntity<>(errorResponse, status);
    }
}
