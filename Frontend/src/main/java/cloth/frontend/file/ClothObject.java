package cloth.frontend.file;

import com.google.common.base.Preconditions;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * An in-memory representation of a single Cloth source file ({@code .co}).
 *
 * <p>A {@code ClothObject} is only ever produced by {@link #of(Path, Path)}, which
 * verifies the file's integrity (extension, existence, UTF-8 validity, non-emptiness)
 * before anything else touches it. Once constructed, the lexer and parser may assume a
 * valid, decoded, non-empty {@link #getSource() source}.
 *
 * <p>The {@link #getModule() module} and {@link #getTypeName() type name} are
 * <em>derived from the file's path</em> relative to a source root (e.g.
 * {@code <root>/cloth/io/Out.co} &rarr; module {@code cloth.io}, type {@code Out}). The
 * {@code module ...;} declaration written inside the file is read later, by the parser,
 * and can be checked against this derived value.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class ClothObject {

	private static final String EXTENSION = ".co";

	/**
	 * Normalized absolute path to the source file.
	 */
	private final Path   path;
	/**
	 * Normalized absolute source root the module was derived against.
	 */
	private final Path   sourceRoot;
	/**
	 * Module derived from the path; {@code ""} for the root module.
	 */
	private final String module;
	/**
	 * Top-level type name: the file name without the {@code .co} extension.
	 */
	private final String typeName;
	/**
	 * Full file contents, decoded as UTF-8.
	 */
	private final String source;

	/**
	 * Load and validate a {@code .co} file.
	 *
	 * @param sourceRoot the root the module name is derived against
	 * @param file       the {@code .co} file to load
	 * @return a validated {@code ClothObject}
	 * @throws ClothObjectException if any integrity check fails
	 */
	public static ClothObject of (Path sourceRoot, Path file) {
		Preconditions.checkNotNull(sourceRoot, "sourceRoot must not be null");
		Preconditions.checkNotNull(file, "file must not be null");

		Path root = sourceRoot.toAbsolutePath().normalize();
		Path target = file.toAbsolutePath().normalize();

		requireClothExtension(target);
		requireReadableFile(target);

		String source = readUtf8(target);
		requireNonEmpty(source, target);

		String module = deriveModule(root, target);
		String typeName = stripExtension(target.getFileName().toString());

		return new ClothObject(target, root, module, typeName, source);
	}

	/**
	 * File name including extension
	 */
	public String fileName () {
		return path.getFileName().toString();
	}

	private static void requireClothExtension (Path file) {
		if (!file.getFileName().toString().endsWith(EXTENSION)) {
			throw new ClothObjectException("Not a Cloth source file (expected " + EXTENSION + "): " + file);
		}
	}

	private static void requireReadableFile (Path file) {
		if (!Files.isRegularFile(file)) {
			throw new ClothObjectException("File does not exist or is not a regular file: " + file);
		}
		if (!Files.isReadable(file)) {
			throw new ClothObjectException("File is not readable: " + file);
		}
	}

	private static void requireNonEmpty (String source, Path file) {
		if (source.isBlank()) {
			throw new ClothObjectException("File is empty: " + file);
		}
	}

	private static String readUtf8 (Path file) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(file);
		} catch (IOException e) {
			throw new ClothObjectException("Failed to read file: " + file, e);
		}

		try {
			return utf8Decoder().decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new ClothObjectException("File is not valid UTF-8: " + file, e);
		}
	}

	private static CharsetDecoder utf8Decoder () {
		return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
	}

	private static String deriveModule (Path root, Path file) {
		Path packageDir = relativizeUnderRoot(root, file).getParent();
		if (packageDir == null) {
			return "";
		}
		return StreamSupport.stream(packageDir.spliterator(), false).map(Path::toString).collect(Collectors.joining("."));
	}

	private static Path relativizeUnderRoot (Path root, Path file) {
		Path relative;
		try {
			relative = root.relativize(file);
		} catch (IllegalArgumentException e) {
			throw new ClothObjectException("File is not under the source root " + root + ": " + file, e);
		}
		if (relative.startsWith("..")) {
			throw new ClothObjectException("File is not under the source root " + root + ": " + file);
		}
		return relative;
	}

	private static String stripExtension (String fileName) {
		return fileName.substring(0, fileName.length() - EXTENSION.length());
	}

}
