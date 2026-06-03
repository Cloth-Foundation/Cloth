package cloth.frontend.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClothObjectTest {

	@Test
	void derivesModuleAndTypeNameFromPath (@TempDir Path root) throws IOException {
		Path file = root.resolve("cloth/io/Out.co");
		Files.createDirectories(file.getParent());
		String source = "module cloth.io;\n\npublic class () {}\n";
		Files.writeString(file, source);

		ClothObject obj = ClothObject.of(root, file);

		assertAll(() -> assertEquals("cloth.io", obj.getModule()), () -> assertEquals("Out", obj.getTypeName()), () -> assertEquals("Out.co", obj.fileName()), () -> assertEquals(source, obj.getSource()));
	}

	@Test
	void fileDirectlyInRootHasEmptyModule (@TempDir Path root) throws IOException {
		Path file = root.resolve("Root.co");
		Files.writeString(file, "module whatever;\n");

		ClothObject obj = ClothObject.of(root, file);

		assertEquals("", obj.getModule());
		assertEquals("Root", obj.getTypeName());
	}

	@Test
	void rejectsWrongExtension (@TempDir Path root) throws IOException {
		Path file = root.resolve("Out.txt");
		Files.writeString(file, "module cloth.io;\n");

		assertThrows(ClothObjectException.class, () -> ClothObject.of(root, file));
	}

	@Test
	void rejectsMissingFile (@TempDir Path root) {
		Path file = root.resolve("Missing.co");

		assertThrows(ClothObjectException.class, () -> ClothObject.of(root, file));
	}

	@Test
	void rejectsBlankFile (@TempDir Path root) throws IOException {
		Path file = root.resolve("Blank.co");
		Files.writeString(file, "   \n\t\n");

		assertThrows(ClothObjectException.class, () -> ClothObject.of(root, file));
	}

	@Test
	void rejectsMalformedUtf8 (@TempDir Path root) throws IOException {
		Path file = root.resolve("Bad.co");
		// 0xFF is never a valid UTF-8 byte.
		Files.write(file, new byte[] { (byte) 0xFF, (byte) 0xFE, 'a', 'b' });

		assertThrows(ClothObjectException.class, () -> ClothObject.of(root, file));
	}

	@Test
	void rejectsFileOutsideSourceRoot (@TempDir Path tmp) throws IOException {
		Path root = tmp.resolve("root");
		Files.createDirectories(root);
		Path file = tmp.resolve("other/Thing.co");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "module x;\n", StandardCharsets.UTF_8);

		assertThrows(ClothObjectException.class, () -> ClothObject.of(root, file));
	}

}
