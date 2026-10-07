package com.ptit.iot.exception;

public class MqttUnavailableException extends RuntimeException {
    public MqttUnavailableException(String message) {
        super(message);
    }

    public MqttUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
