package com.micropass.web;

import com.micropass.service.DeploymentPipelineService.ActiveDeploymentException;
import com.micropass.service.DeploymentPipelineService.DeploymentQueueUnavailableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "Request validation failed", fields);
    }

    @ExceptionHandler(ActiveDeploymentException.class)
    public ResponseEntity<Map<String, Object>> activeDeployment(ActiveDeploymentException exception) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), null);
    }

    @ExceptionHandler(DeploymentQueueUnavailableException.class)
    public ResponseEntity<Map<String, Object>> queueUnavailable(DeploymentQueueUnavailableException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), null);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> invalidRequest(InvalidRequestException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage(), null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException exception) {
        return response(HttpStatus.CONFLICT, "A record with the same unique value already exists", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException exception) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), null);
    }

    private ResponseEntity<Map<String, Object>> response(
            HttpStatus status,
            String message,
            Map<String, String> fieldErrors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            body.put("fieldErrors", fieldErrors);
        }
        return ResponseEntity.status(status).body(body);
    }
}
