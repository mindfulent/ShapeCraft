package com.shapecraft.generation;

/**
 * Thrown when the backend returns a non-200 HTTP status code.
 * Carries the status code so callers can distinguish trial exhaustion (402)
 * from other errors.
 */
public class BackendHttpException extends RuntimeException {
    private final int statusCode;

    public BackendHttpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
