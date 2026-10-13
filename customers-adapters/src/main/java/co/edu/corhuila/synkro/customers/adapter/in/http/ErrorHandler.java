package co.edu.corhuila.synkro.customers.adapter.in.http;

import co.edu.corhuila.synkro.customers.domain.model.CustomerFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import java.util.*;

@RestControllerAdvice
public class ErrorHandler {
    @ExceptionHandler(CustomerFailure.class) public ResponseEntity<?> domain(CustomerFailure e,HttpServletRequest r) {
        int status=switch(e.code()){case "VALIDATION_ERROR" -> 400;case "FORBIDDEN" -> 403;case "NOT_FOUND" -> 404;case "BUSINESS_RULE_VIOLATION" -> 422;default -> 500;};
        return ResponseEntity.status(status).body(HttpErrors.body(r,e.code(),e.getMessage(),e.field()==null?List.of():List.of(Map.of("field",e.field(),"message",e.getMessage()))));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<?> validation(MethodArgumentNotValidException e,HttpServletRequest r) {
        var details=e.getBindingResult().getFieldErrors().stream().map(f -> Map.of("field",f.getField(),"message","Invalid field value")).toList();
        return ResponseEntity.badRequest().body(HttpErrors.body(r,"VALIDATION_ERROR","Invalid request fields",details));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<?> malformed(HttpServletRequest r) {
        return ResponseEntity.badRequest().body(HttpErrors.body(r,"VALIDATION_ERROR","Invalid JSON request",List.of(Map.of("field","body","message","Invalid JSON request"))));
    }
    @ExceptionHandler(NoResourceFoundException.class) public ResponseEntity<?> missing(HttpServletRequest r) {
        return ResponseEntity.status(404).body(HttpErrors.body(r,"NOT_FOUND","Resource not found",List.of()));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) public ResponseEntity<?> method(HttpServletRequest r) {
        return ResponseEntity.status(405).body(HttpErrors.body(r,"VALIDATION_ERROR","Unsupported HTTP method",List.of()));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> internal(Exception e,HttpServletRequest r) {
        if(e instanceof org.springframework.web.ErrorResponse error && error.getStatusCode().is4xxClientError())
            return ResponseEntity.status(error.getStatusCode()).body(HttpErrors.body(r,"VALIDATION_ERROR","Invalid HTTP request",List.of()));
        return ResponseEntity.internalServerError().body(HttpErrors.body(r,"INTERNAL_ERROR","Request could not be completed",List.of()));
    }
}
