package cz.domaci.odmeny.settings;

/**
 * Invalid input or a missing record on a settings screen. The web layer maps
 * this to a fixed flash message; the message here is for logs and tests.
 */
public class InvalidSettingsException extends RuntimeException {

    public InvalidSettingsException(String message) {
        super(message);
    }
}
