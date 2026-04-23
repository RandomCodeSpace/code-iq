package io.github.randomcodespace.iq.config.unified;

public class ConfigLoadException extends RuntimeException {
    public ConfigLoadException(String message, Throwable cause) { super(message, cause); }
    public ConfigLoadException(String message) { super(message); }
}
