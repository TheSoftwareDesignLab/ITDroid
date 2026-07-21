package uniandes.tsdl.itdroid.helper;

/** Thrown when a language version cannot be explored on the device. */
public class ExplorationException extends Exception {

	private static final long serialVersionUID = 1L;

	public ExplorationException(String errorMessage) {
		super(errorMessage);
	}

	public ExplorationException(String errorMessage, Throwable cause) {
		super(errorMessage, cause);
	}
}
