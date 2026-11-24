package com.campussphere.backend.controller;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.campussphere.backend.security.ApiError;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiError.Failure.class)
    ResponseEntity<ApiError.Body> applicationError(ApiError.Failure failure) {
        return response(failure.error());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError.Body> invalidRequest() {
        return response(ApiError.INVALID_REQUEST);
    }

    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class,
            RecoverableDataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiError.Body> databaseUnavailable() {
        return response(ApiError.DATABASE_UNAVAILABLE);
    }

    private ResponseEntity<ApiError.Body> response(ApiError error) {
        var response = ResponseEntity.status(error.status());
        if (error == ApiError.INVALID_AUTHENTICATION) {
            response.header("WWW-Authenticate", "Bearer");
        }
        return response.body(error.body());
    }
}
