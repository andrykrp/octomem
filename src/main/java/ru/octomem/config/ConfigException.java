package ru.octomem.config;

/** Checked configuration failure containing only trusted diagnostics, never input values or causes. */
public final class ConfigException extends Exception {
    private static final long serialVersionUID = 1L;

    ConfigException(String message) {
        super(message);
    }
}
