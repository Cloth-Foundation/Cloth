/*
 * Part of the Cloth Compiler, under the Apache License v2.0 with LLVM Exceptions.
 * SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
 */

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Isolated dependency-selection probe; this is not Shuttle's manifest loader. */
public class TomlParserProbe {
    private static int checks;
    private static final TomlFactory FACTORY = TomlFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final TomlMapper MAPPER = new TomlMapper(FACTORY);

    public static void main(String[] args) throws Exception {
        Path repository = Path.of(args.length == 0 ? "." : args[0]);
        require(MAPPER.version().toString().equals("2.21.1"), "pinned TOML dependency version");
        for (String manifest : new String[]{"build.toml", "Frontend/module.toml",
                "Compiler/module.toml", "Backend/module.toml"}) {
            require(MAPPER.readTree(Files.readString(repository.resolve("Bootstrap").resolve(manifest)))
                    .isObject(), "Bootstrap " + manifest);
        }

        JsonNode standard = MAPPER.readTree("""
                # Comment outside strings
                [project]
                name = "Cloth # text"
                version = '0.0.1'
                enabled = true
                roots = ["Frontend", "Compiler",]
                [modules]
                Frontend = "Frontend"
                "quoted.key" = { path = 'folder with spaces', active = true }
                [text]
                unicode = "Cloth \u03bb \ud83d\ude80"
                multiline = """ + "\"\"\"\nfirst\nsecond\n\"\"\"\n");
        require(standard.at("/project/name").asText().equals("Cloth # text"), "comments and strings");
        require(standard.at("/project/roots").size() == 2, "standard array trailing comma");
        require(standard.at("/modules/quoted.key/path").asText().equals("folder with spaces"),
                "quoted keys, inline tables and literal strings");
        require(standard.at("/text/unicode").asText().equals("Cloth \u03bb \ud83d\ude80"), "Unicode values");
        require(standard.at("/text/multiline").asText().equals("first\nsecond\n"), "multiline strings");
        require(MAPPER.readTree("[project]\r\nname = 'Cloth'\r\n").at("/project/name").asText()
                .equals("Cloth"), "CRLF input");

        reject("duplicate key", "name = 'a'\nname = 'b'\n", 0);
        reject("duplicate table", "[project]\nname = 'a'\n[project]\nversion = 'b'\n", 0);
        reject("table/value conflict", "project = 'a'\n[project]\nname = 'b'\n", 0);
        reject("malformed first value", "[project]\nname = @\nversion = @\n", 2);
        reject("malformed CRLF", "[project]\r\nname = @\r\nversion = @\r\n", 2);
        reject("TOML 1.1 escape", "name = \"\\x41\"\n", 1);
        reject("TOML 1.1 multiline inline table", "entry = {\nname = 'a'\n}\n", 0);

        String firstBad = "[project]\nname = @\n\n";
        try (Reader gated = new Reader() {
            private int offset;
            @Override public int read(char[] chars, int start, int length) {
                if (length == 0) return 0;
                if (offset >= firstBad.length()) {
                    throw new AssertionError("Parser read past the first malformed declaration");
                }
                chars[start] = firstBad.charAt(offset++);
                return 1;
            }
            @Override public void close() {}
        }) {
            try {
                MAPPER.readTree(gated);
                throw new AssertionError("Malformed declaration was accepted");
            } catch (StreamReadException failure) {
                require(failure.getLocation().getLineNr() == 2,
                        "first failure aborts before any later declaration is read");
            }
        }

        try (JsonParser parsed = FACTORY.createParser("[project]\nname = 'Cloth'\n")) {
            parsed.nextToken();
            JsonLocation position = parsed.currentTokenLocation();
            require(position.getLineNr() < 1, "public success tree does not retain source positions");
        }
        System.out.println("Passed " + checks + " TOML selection checks (Jackson TOML 2.21.1).");
        System.out.println("First-error abort and syntax locations verified; success-tree field positions unavailable.");
    }

    private static void reject(String name, String source, int expectedLine) throws Exception {
        try {
            MAPPER.readTree(source);
            throw new AssertionError(name + " was accepted");
        } catch (StreamReadException failure) {
            require(failure.getLocation() != null && failure.getLocation().getLineNr() >= 1
                    && failure.getLocation().getColumnNr() >= 1, name + " located failure");
            if (expectedLine > 0) {
                require(failure.getLocation().getLineNr() == expectedLine, name + " first-error line");
            }
        }
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
}
