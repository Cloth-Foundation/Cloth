package cloth.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Loads the installed application without the Windows system classpath URL encoder.
 */
public final class Bootstrap {

	public static void main (String[] arguments) throws Throwable {
		String entry = arguments[0];
		Path library = Path.of(arguments[1]);
		try {
			var loader = new URLClassLoader(jars(library), ClassLoader.getSystemClassLoader());
			Thread.currentThread().setContextClassLoader(loader);
			Method main = Class.forName(entry, false, loader).getMethod("main", String[].class);
			main.invoke(null, (Object) Arrays.copyOfRange(arguments, 2, arguments.length));
		} catch (InvocationTargetException failure) {
			if (!(failure.getCause() instanceof LinkageError missingDependency)) throw failure.getCause();
			fail(entry, library, missingDependency);
		} catch (IOException | UncheckedIOException | ReflectiveOperationException | LinkageError failure) {
			fail(entry, library, failure);
		}
	}

	private static URL[] jars (Path library) throws IOException {
		try (var files = Files.list(library)) {
			var paths = files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).filter(Files::isRegularFile).sorted().toList();
			var urls = new URL[paths.size()];
			for (int index = 0; index < paths.size(); index++) urls[index] = paths.get(index).toUri().toURL();
			return urls;
		}
	}

	private static void fail (String entry, Path library, Throwable failure) {
		System.err.println("Cloth launcher: unable to load " + entry + " from '" + library + "' (" + failure + ")");
		System.exit(2);
	}

}
