package cloth.frontend.file;

/**
 * Thrown when a {@code .co} file fails an integrity check while being loaded into a
 * {@link ClothObject}, before lexing begins. Unchecked so the loading factory keeps a
 * clean signature; wraps the underlying cause (IO / decoding) where there is one.
 */
public class ClothObjectException extends RuntimeException {

	public ClothObjectException(String message) {
		super(message);
	}

	public ClothObjectException(String message, Throwable cause) {
		super(message, cause);
	}

}
