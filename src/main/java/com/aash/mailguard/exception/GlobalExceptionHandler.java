package com.aash.mailguard.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.LocalDateTime;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest request) {
        HttpStatus status = ex instanceof MethodArgumentNotValidException || ex instanceof HttpMessageNotReadableException
                ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.setMessage(status == HttpStatus.BAD_REQUEST ? "Invalid request" : "Internal server error");
        errorResponse.setTimestamp(LocalDateTime.now().toString());
        errorResponse.setStatus(status.value());
        String path = request.getRequestURI();
        errorResponse.setPath(path.equals("/api/email/validate") || path.equals("/api/email/test") ? path : "/");
        return new ResponseEntity<>(errorResponse, status);
    }
}
