package com.policy.rag.app.exception;

public class LowConfidenceException extends RuntimeException {

    public LowConfidenceException(String message) {
        super(message);
    }
}
