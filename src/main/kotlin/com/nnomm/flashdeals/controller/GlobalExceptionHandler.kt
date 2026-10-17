package com.nnomm.flashdeals.controller

import com.nnomm.flashdeals.auth.AuthenticationException
import com.nnomm.flashdeals.auth.ConflictException
import com.nnomm.flashdeals.auth.ForbiddenException
import com.nnomm.flashdeals.auth.IdempotencyKeyInvalidException
import com.nnomm.flashdeals.auth.IdempotencyKeyReusedException
import com.nnomm.flashdeals.auth.NotFoundException
import com.nnomm.flashdeals.auth.PayloadTooLargeException
import com.nnomm.flashdeals.auth.ValidationException
import com.nnomm.flashdeals.models.ErrorDetails
import com.nnomm.flashdeals.models.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(e: ValidationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(ErrorDetails("VALIDATION_FAILED", e.message ?: "Validation failed")))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class)
    fun handleSpringValidation(e: Exception): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(ErrorDetails("VALIDATION_FAILED", e.message ?: "Invalid request format")))
    }

    @ExceptionHandler(IdempotencyKeyInvalidException::class)
    fun handleIdempotencyKeyInvalid(e: IdempotencyKeyInvalidException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(ErrorDetails("IDEMPOTENCY_KEY_INVALID", e.message ?: "Invalid Idempotency-Key")))
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuth(e: AuthenticationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(ErrorDetails("UNAUTHENTICATED", e.message ?: "Unauthenticated")))
    }

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(e: ForbiddenException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse(ErrorDetails("FORBIDDEN", e.message ?: "Forbidden")))
    }

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(e: NotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(ErrorDetails("NOT_FOUND", e.message ?: "Not found")))
    }

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(e: ConflictException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(ErrorDetails(e.code, e.message ?: "Conflict")))
    }

    @ExceptionHandler(PayloadTooLargeException::class)
    fun handlePayloadTooLarge(e: PayloadTooLargeException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ErrorResponse(ErrorDetails("PAYLOAD_TOO_LARGE", e.message ?: "The body is over 16 KB")))
    }

    @ExceptionHandler(IdempotencyKeyReusedException::class)
    fun handleIdempotencyReused(e: IdempotencyKeyReusedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse(ErrorDetails("IDEMPOTENCY_KEY_REUSED", e.message ?: "Idempotency key reused")))
    }

    @ExceptionHandler(Throwable::class)
    fun handleGeneral(e: Throwable): ResponseEntity<ErrorResponse> {
        log.error("Unhandled internal server error: ${e.message}", e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(ErrorDetails("INTERNAL", "An unexpected internal error occurred")))
    }
}
