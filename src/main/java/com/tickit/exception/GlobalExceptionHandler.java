package com.tickit.exception;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  @ExceptionHandler(SeatAlreadyBookedException.class)
  public ResponseEntity<Map<String, Object>> handleSeatAlreadyBooked(
      SeatAlreadyBookedException ex) {
    log.warn("Seat already booked: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(409, ex.getMessage()));
  }

  @ExceptionHandler(SeatHoldExpiredException.class)
  public ResponseEntity<Map<String, Object>> handleHoldExpired(SeatHoldExpiredException ex) {
    log.warn("Seat hold expired: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.GONE).body(errorBody(410, ex.getMessage()));
  }

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException ex) {
    log.warn("Rate limit exceeded: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", "10")
        .body(errorBody(429, ex.getMessage()));
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, Object>> handleIntegrityConflict(Exception ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(errorBody(409, "Seat already booked or concurrent booking conflict"));
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<Map<String, Object>> handleOccConflict(
      OptimisticLockingFailureException ex) {
    log.warn("Optimistic lock conflict: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(errorBody(409, "Concurrent booking conflict – please retry"));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
    String msg =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
    return ResponseEntity.badRequest().body(errorBody(400, msg));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, Object>> handleUploadLimit(Exception ex) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(errorBody(413, "Maximum file size is 5 MB"));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception ex) {
    return ResponseEntity.badRequest().body(errorBody(400, "Missing or invalid request parameter"));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMethod(
      org.springframework.web.HttpRequestMethodNotSupportedException ex) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .headers(ex.getHeaders())
        .body(errorBody(405, "HTTP method is not supported"));
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<Map<String, Object>> handleAccept(Exception ex) {
    return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(errorBody(406, "Requested response format is not available"));
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMedia(Exception ex) {
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(errorBody(415, "Use the required request content type"));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<Map<String, Object>> handleMissingResource(Exception ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(404, "Resource not found"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(errorBody(400, ex.getMessage()));
  }

  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> handleStatus(
      org.springframework.web.server.ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode())
        .body(
            errorBody(
                ex.getStatusCode().value(),
                ex.getReason() == null ? "Request failed" : ex.getReason()));
  }

  @ExceptionHandler({
    org.springframework.dao.DataAccessResourceFailureException.class,
    org.springframework.dao.QueryTimeoutException.class,
    org.springframework.transaction.CannotCreateTransactionException.class
  })
  public ResponseEntity<Map<String, Object>> handleDependencyFailure(Exception ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(errorBody(503, "Reservation infrastructure is temporarily unavailable"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(errorBody(500, "Internal server error"));
  }

  private Map<String, Object> errorBody(int status, String message) {
    return Map.of(
        "status", status,
        "error", message,
        "timestamp", LocalDateTime.now().toString());
  }
}
