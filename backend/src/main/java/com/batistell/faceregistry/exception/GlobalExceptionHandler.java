package com.batistell.faceregistry.exception;

import com.batistell.faceregistry.exception.CustomExceptions.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorPayload(
            int status,
            String error,
            String message,
            LocalDateTime timestamp
    ) {}

    @ExceptionHandler(NoFaceDetectedException.class)
    public ResponseEntity<ErrorPayload> handleNoFace(NoFaceDetectedException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MultipleFacesDetectedException.class)
    public ResponseEntity<ErrorPayload> handleMultipleFaces(MultipleFacesDetectedException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(InvalidImageException.class)
    public ResponseEntity<ErrorPayload> handleInvalidImage(InvalidImageException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorPayload> handleUserNotFound(UserNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateCpfException.class)
    public ResponseEntity<ErrorPayload> handleDuplicateCpf(DuplicateCpfException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(DuplicateFaceException.class)
    public ResponseEntity<ErrorPayload> handleDuplicateFace(DuplicateFaceException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidCpfException.class)
    public ResponseEntity<ErrorPayload> handleInvalidCpf(InvalidCpfException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(BiometricProcessingException.class)
    public ResponseEntity<ErrorPayload> handleBiometricProcessing(BiometricProcessingException ex) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorPayload> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        String combinedMessage = errors.values().stream()
                .findFirst()
                .orElse("Erro de validação de dados.");

        return buildResponse(HttpStatus.BAD_REQUEST, combinedMessage);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorPayload> handleGeneralException(Exception ex) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Ocorreu um erro interno no servidor: " + ex.getMessage());
    }

    private ResponseEntity<ErrorPayload> buildResponse(HttpStatus status, String message) {
        ErrorPayload payload = new ErrorPayload(
                status.value(),
                status.getReasonPhrase(),
                message,
                LocalDateTime.now()
        );
        return new ResponseEntity<>(payload, status);
    }
}
