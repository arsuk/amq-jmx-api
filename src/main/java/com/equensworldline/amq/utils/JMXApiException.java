package com.equensworldline.amq.utils;

public class JMXApiException extends RuntimeException {
    public JMXApiException() {
        super();
    }

    public JMXApiException(String message) {
        super(message);
    }

    public JMXApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public JMXApiException(Throwable cause) {
        super(cause);
    }

    protected JMXApiException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
