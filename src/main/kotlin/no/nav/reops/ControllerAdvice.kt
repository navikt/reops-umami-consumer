package no.nav.reops

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class ControllerAdvice {
    private val logger: Logger = LoggerFactory.getLogger(ControllerAdvice::class.java)

    @ExceptionHandler(Exception::class)
    fun handleGeneralException(ex: Exception): ResponseEntity<String> {
        logger.error(ex.message, ex)
        return ResponseEntity("Noe uventet feilet: " + ex.message, HttpStatus.INTERNAL_SERVER_ERROR)
    }
}