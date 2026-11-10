package cloth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ToolchainDistributionTest {
    @TempDir Path temporary;
    private final Path installed = Path.of(System.getProperty("cloth.test.toolchain"));
    private final boolean windows = System.getProperty("os.name").startsWith("Windows");

    @Test void installedBundleContainsBothCommandsPinnedSourcesAndProvenance() throws IOException {
        assertTrue(Files.isRegularFile(installed.resolve("bin/cloth")));
        assertTrue(Files.isRegularFile(installed.resolve("bin/cloth.bat")));
        assertTrue(Files.isExecutable(installed.resolve(windows ? "bin/shuttle.exe" : "bin/shuttle")));
        assertFalse(Files.exists(installed.resolve("bin/shuttle.bat")));
        try (var files = Files.list(installed.resolve("lib"))) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith("jackson-")));
        }
        assertTrue(Files.isExecutable(installed.resolve(windows ? "runtime/bin/java.exe" : "runtime/bin/java")));
        assertTrue(Files.isRegularFile(installed.resolve("stdlib/module.toml")));
        assertTrue(Files.isRegularFile(installed.resolve("stdlib/cloth/Library.co")));
        var metadata = new Properties();
        try (var reader = Files.newBufferedReader(installed.resolve("toolchain.properties"))) { metadata.load(reader); }
        assertEquals("1", metadata.getProperty("format"));
        assertFalse(metadata.getProperty("compiler.version").isBlank());
        assertFalse(metadata.getProperty("stdlib.version").isBlank());
        assertTrue(metadata.getProperty("stdlib.revision").matches("[0-9a-f]{40,64}"));
        try (var files = Files.walk(installed.resolve("stdlib"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().equals(".git") || path.getFileName().toString().equals("build")));
        }
    }

    @Test void installedShuttleRunsImportedGenericInterfaceFixture() throws Exception {
        Path project = Path.of(System.getProperty("cloth.test.workspace"), "examples", "Interfaces");
        String report = run(installed, temporary, "shuttle", "run", "--project", project.toString(), "-o", "programs/interfaces");
        assertTrue(report.replace("\r\n", "\n").contains("from library: ready\nSlot<Value<string>>\n\nPEC: 0\n"), report);
    }

    @Test void installedCommandsRunPublishedStateFixtures() throws Exception {
        Path examples = Path.of(System.getProperty("cloth.test.workspace"), "examples");
        var targets = new ArrayList<>(List.of("native"));
        if (windows && System.getProperty("os.arch").equals("amd64")) targets.add("i686-pc-windows-msvc");
        for (String target : targets) {
            var clothArgs = new ArrayList<>(List.of("run", examples.resolve("States.co").toString(), "programs/nested-" + target));
            var shuttleArgs = new ArrayList<>(List.of("run", "--project", examples.resolve("States").toString(), "-o", "programs/attached-" + target));
            if (!target.equals("native")) {
                clothArgs.addAll(List.of("--target", target));
                shuttleArgs.addAll(List.of("--target", target));
            }
            String nested = run(installed, temporary, "cloth", clothArgs.toArray(String[]::new)).replace("\r\n", "\n");
            assertTrue(nested.endsWith("Started report\nClosed report\nreport done\n[1, 2]\n\nPEC: 0\n"), nested);
            String attached = run(installed, temporary, "shuttle", shuttleArgs.toArray(String[]::new)).replace("\r\n", "\n");
            assertTrue(attached.endsWith("Worker desk\nWorker remote\n[desk]\n[parcel]\n\nPEC: 0\n"), attached);
        }
    }

    @Test void installedCommandsReportTheFirstStateErrorAndPropagateRuntimeFailure() throws Exception {
        Path source = temporary.resolve("Main.co");
        Files.writeString(temporary.resolve("build.toml"), "[project]\nname = \"StateErrors\"\nentryPoint = \"App.Main.main\"\n[modules]\nApp = \".\"\n");
        Files.writeString(temporary.resolve("module.toml"), "[module]\nname = \"App\"\n");
        String types = """
                typedef<class> Job {
                    default: state<void> Ready {
                        pub mt run(int marker) { println("body ran") }
                    }
                    pub state<void> Closed {}
                }
                """;
        Files.writeString(source, types + """
                fn main() {
                    def job = Job::new
                    job -> Closed
                    job::run(1)
                    job::run(2)
                }
                """);
        for (String command : List.of("cloth", "shuttle")) {
            String output = "objects/" + command + ".o";
            String[] args = command.equals("cloth") ? new String[]{"--emit", "object", "Main.co", output}
                    : new String[]{"build", "--emit", "object", "-o", output};
            String report = runExpected(1, installed, temporary, command, args);
            var codes = Pattern.compile("\\b[CS]\\d{4}\\b").matcher(report).results().map(MatchResult::group).toList();
            assertEquals(List.of("C3130"), codes, report);
            assertTrue(report.contains("Main.co"), report);
            assertFalse(Files.exists(temporary.resolve(output)));
        }

        Files.writeString(source, types + """
                fn<int> close(Job job) {
                    job -> Closed
                    println("Changed to Closed")
                    return 1
                }
                fn main() {
                    def job = Job::new
                    job::run(close(job))
                }
                """);
        for (String command : List.of("cloth", "shuttle")) {
            String output = "programs/" + command + "-state-error";
            String[] args = command.equals("cloth") ? new String[]{"run", "Main.co", output}
                    : new String[]{"run", "-o", output};
            String report = runExpected(1, installed, temporary, command, args).replace("\r\n", "\n");
            assertTrue(report.endsWith("Changed to Closed\nInvalid state for 'run(int)'\n"), report);
            assertFalse(report.contains("body ran"), report);
        }
    }

    @Test void relocatedCommandsPreserveLocationAndInvocationDirectory() throws Exception {
        Path workspaceScratch = Path.of(System.getProperty("cloth.test.workspace"), "tmp").toRealPath();
        assertTrue(temporary.toRealPath().startsWith(workspaceScratch));
        Path staged = temporary.resolve("staged");
        copyBundle(staged);
        var nativeEncoder = Charset.forName(System.getProperty("native.encoding", "UTF-8")).newEncoder();
        String supportedUnicode = Arrays.stream(new String[]{"é", "移", "Ж", "Ω", "가", "א", "\u00a0"})
                .filter(nativeEncoder::canEncode).findFirst().orElse("");
        Path relocated = temporary.resolve("Relocated Cloth" + (supportedUnicode.isEmpty() ? "" : " " + supportedUnicode));
        assertTrue(staged.normalize().startsWith(temporary) && relocated.normalize().startsWith(temporary));
        Files.move(staged, relocated);
        Path client = Files.createDirectory(temporary.resolve("client"));
        Files.writeString(client.resolve("Main.co"), "fn<int> main() = 0\n");
        Files.writeString(client.resolve("build.toml"), "[project]\nname = \"Client\"\nentryPoint = \"App.Main.main\"\n[modules]\nApp = \".\"\n");
        Files.writeString(client.resolve("module.toml"), "[module]\nname = \"App\"\n");
        String compilerOutput = run(relocated, client, "cloth", "--emit", "object", "Main.co", "objects/compiler.o");
        assertLocation(compilerOutput, relocated);
        assertTrue(Files.size(client.resolve("objects/compiler.o")) > 0);
        String shuttleOutput = run(relocated, client, "shuttle", "build", "--project", ".", "--emit", "object", "-o", "objects/shuttle.o");
        assertLocation(shuttleOutput, relocated);
        assertTrue(Files.size(client.resolve("objects/shuttle.o")) > 0);
    }

    @Test void bothCommandsImportReachableLibrarySourcesWithoutManifestEntries() throws Exception {
        Path bundle = temporary.resolve("bundle");
        copyBundle(bundle);
        Path client = clientWithLibraryFixture(bundle);
        assertTrue(run(bundle, client, "cloth", "run", "Main.co", "programs/compiler").contains("PEC: 0"));
        assertTrue(run(bundle, client, "shuttle", "run", "--project", ".", "-o", "programs/shuttle").contains("PEC: 0"));

        // An existing entry for exactly the selected root is promoted, not duplicated.
        Files.writeString(client.resolve("build.toml"), Files.readString(client.resolve("build.toml"))
                + "StandardLibrary = \"" + bundle.resolve("stdlib").toString().replace('\\', '/') + "\"\n");
        run(bundle, client, "shuttle", "build", "--emit", "object", "-o", "objects/promoted.o");
        assertTrue(Files.size(client.resolve("objects/promoted.o")) > 0);

        Files.writeString(client.resolve("Main.co"), "fn<int> main() = answer()\n");
        assertTrue(runExpected(1, bundle, client, "cloth", "--emit", "object", "Main.co", "objects/unimported.o").contains("C3020"));
        assertTrue(runExpected(1, bundle, client, "shuttle", "build", "--emit", "object", "-o", "objects/unimported.o").contains("C3020"));
        assertFalse(Files.exists(client.resolve("objects/unimported.o")));
    }

    @Test void collisionsAndIncompleteBundlesFailOnceBeforeOutputs() throws Exception {
        Path bundle = temporary.resolve("bundle");
        copyBundle(bundle);
        Path client = clientWithLibraryFixture(bundle);
        Files.createDirectories(client.resolve("cloth/testing"));
        Files.writeString(client.resolve("cloth/testing/Answer.co"), "@ invalid conflicting source\n");
        for (String command : List.of("cloth", "shuttle")) {
            String target = "objects/" + command + "-collision.o";
            String[] args = command.equals("cloth") ? new String[]{"--emit", "object", "Main.co", target}
                    : new String[]{"build", "--emit", "object", "-o", target};
            // Standalone supplies the library root; only Shuttle configures this project's root.
            String output = runExpected(command.equals("cloth") ? 0 : 1, bundle, client, command, args);
            if (command.equals("shuttle")) {
                assertEquals(1, output.split("C3106", -1).length - 1, output);
                assertFalse(Files.exists(client.resolve(target)));
            } else assertTrue(Files.size(client.resolve(target)) > 0);
        }
        String metadata = Files.readString(bundle.resolve("toolchain.properties"));
        assertTrue(runExpected(1, bundle, client, "cloth", "--emit", "object", "Main.co", bundle.resolve("toolchain.properties").toString()).contains("C0020"));
        assertTrue(runExpected(1, bundle, client, "shuttle", "build", "--emit", "object", "-o", bundle.resolve("toolchain.properties").toString()).contains("C0020"));
        assertEquals(metadata, Files.readString(bundle.resolve("toolchain.properties")));

        Files.delete(bundle.resolve("stdlib/module.toml"));
        Files.writeString(client.resolve("Main.co"), "@");
        Files.writeString(client.resolve("build.toml"), "[broken");
        for (String command : List.of("cloth", "shuttle")) {
            String[] args = command.equals("cloth") ? new String[]{"--emit", "object", "Main.co", "objects/missing.o"}
                    : new String[]{"build", "--emit", "object", "-o", "objects/missing.o"};
            String output = runExpected(1, bundle, client, command, args);
            assertEquals(1, output.split("C0018", -1).length - 1, output);
            assertFalse(Files.exists(client.resolve("objects/missing.o")));
        }
    }

    @Test void explicitOverridesResolveFromInvocationDirectoryAndBypassBrokenDefaults() throws Exception {
        Path bundle = temporary.resolve("bundle");
        copyBundle(bundle);
        Path client = clientWithLibraryFixture(bundle);
        Path override = temporary.resolve("Library checkout 移 🧵");
        Files.move(bundle.resolve("stdlib"), override);
        Files.writeString(override.resolve("module.toml"), "development metadata is not parsed during selection\n");
        Files.writeString(bundle.resolve("toolchain.properties"), "format=broken\n");
        String relative = client.relativize(override).toString();
        assertTrue(run(bundle, client, "cloth", "run", "Main.co", "programs/override-compiler", "--stdlib=missing", "--stdlib", relative).contains("PEC: 0"));
        assertTrue(run(bundle, client, "shuttle", "run", "-o", "programs/override-shuttle", "--stdlib=missing", "--stdlib=" + relative).contains("PEC: 0"));

        for (String command : List.of("cloth", "shuttle")) {
            String[] args = command.equals("cloth") ? new String[]{"--stdlib=" + override, "--emit=object", "Main.co", override.resolve("module.toml").toString()}
                    : new String[]{"build", "--stdlib", relative, "--emit=object", "-o", override.resolve("cloth/Library.co").toString()};
            assertTrue(runExpected(1, bundle, client, command, args).contains("C0020"));
        }

        // A structurally valid checkout reports its reached source's original diagnostic.
        Files.writeString(override.resolve("cloth/testing/Helper.co"), "pub fn<int> value() = unknownFromOverride\n");
        for (String command : List.of("cloth", "shuttle")) {
            String[] args = command.equals("cloth") ? new String[]{"--stdlib=" + relative, "--emit=object", "Main.co", "objects/incompatible.o"}
                    : new String[]{"build", "--stdlib=" + relative, "--emit=object", "-o", "objects/incompatible.o"};
            String output = runExpected(1, bundle, client, command, args);
            assertEquals(1, output.split("C3020", -1).length - 1, output);
            assertTrue(output.contains("Helper.co"), output);
            assertFalse(Files.exists(client.resolve("objects/incompatible.o")));
        }

        // Even with a valid default restored, an invalid override never falls back.
        Files.createDirectories(bundle.resolve("stdlib/cloth"));
        Files.writeString(bundle.resolve("stdlib/module.toml"), "[module]\nname = \"StandardLibrary\"\n");
        Files.writeString(bundle.resolve("stdlib/cloth/Library.co"), "// marker\n");
        Files.copy(installed.resolve("toolchain.properties"), bundle.resolve("toolchain.properties"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(client.resolve("Main.co"), "@");
        Files.writeString(client.resolve("build.toml"), "[broken");
        for (String command : List.of("cloth", "shuttle")) {
            String[] args = command.equals("cloth") ? new String[]{"--stdlib=missing", "--emit=object", "Main.co", "objects/invalid-override.o"}
                    : new String[]{"build", "--stdlib=missing", "--emit=object", "-o", "objects/invalid-override.o"};
            String output = runExpected(1, bundle, client, command, args);
            assertEquals(1, output.split("C0018", -1).length - 1, output);
            assertTrue(output.contains(client.resolve("missing").toString()), output);
            assertFalse(Files.exists(client.resolve("objects/invalid-override.o")));
        }
    }

    @Test void disablingKeepsConfiguredReplacementModulesBuiltinsAndLiteralRunArguments() throws Exception {
        Path bundle = temporary.resolve("bundle");
        copyBundle(bundle);
        Path client = clientWithLibraryFixture(bundle);
        Path replacement = temporary.resolve("replacement");
        Files.move(bundle.resolve("stdlib"), replacement);
        Files.writeString(client.resolve("build.toml"), Files.readString(client.resolve("build.toml"))
                + "StandardLibrary = \"" + replacement.toString().replace('\\', '/') + "\"\n");
        assertTrue(run(bundle, client, "shuttle", "run", "--no-stdlib", "--no-stdlib", "-o", "programs/replacement").contains("PEC: 0"));
        assertTrue(runExpected(1, bundle, client, "cloth", "--no-stdlib", "--emit=object", "Main.co", "objects/disabled.o").contains("C3097"));
        assertFalse(Files.exists(client.resolve("objects/disabled.o")));

        // The explicit StandardLibrary ID stays regular, so duplicates use ordinary ambiguity.
        Files.createDirectories(client.resolve("cloth/testing"));
        Files.writeString(client.resolve("cloth/testing/Answer.co"), "@ must not be parsed\n");
        String ambiguity = runExpected(1, bundle, client, "shuttle", "build", "--no-stdlib", "--emit=object", "-o", "objects/ambiguous.o");
        assertTrue(ambiguity.contains("C3099"), ambiguity);
        assertFalse(ambiguity.contains("C3106"), ambiguity);
        assertFalse(Files.exists(client.resolve("objects/ambiguous.o")));

        Files.writeString(client.resolve("Main.co"), """
                fn<int> main(string[] args) {
                    if (args::size != 2) return 1
                    if (args[0] != "--stdlib=application" || args[1] != "--no-stdlib") return 2
                    println("builtins remain available")
                    return 0
                }
                """);
        for (String command : List.of("cloth", "shuttle")) {
            String[] args = command.equals("cloth") ? new String[]{"run", "--no-stdlib", "Main.co", "programs/disabled-compiler", "--", "--stdlib=application", "--no-stdlib"}
                    : new String[]{"run", "--no-stdlib", "-o", "programs/disabled-shuttle", "--", "--stdlib=application", "--no-stdlib"};
            String output = run(bundle, client, command, args);
            assertTrue(output.contains("builtins remain available"), output);
            assertTrue(output.contains("PEC: 0"), output);
        }
    }

    @Test void selectionOptionErrorsPrecedeDiscoveryAndHelpNeedsNoBundle() throws Exception {
        Path bundle = temporary.resolve("bundle");
        copyBundle(bundle);
        Path client = clientWithLibraryFixture(bundle);
        Files.delete(bundle.resolve("stdlib/module.toml"));
        Files.writeString(client.resolve("Main.co"), "@");
        Files.writeString(client.resolve("build.toml"), "[broken");
        for (String command : List.of("cloth", "shuttle")) {
            for (List<String> flags : List.of(List.of("--stdlib=missing", "--no-stdlib"), List.of("--no-stdlib", "--stdlib=missing"), List.of("--no-stdlib=false"), List.of("--stdlib="), List.of("--stdlib", "--project=.", "later-path"))) {
                var args = new ArrayList<String>();
                if (command.equals("shuttle")) args.add("build");
                args.addAll(flags);
                String output = runExpected(1, bundle, client, command, args.toArray(String[]::new));
                String code = flags.getFirst().equals("--stdlib=") || flags.getFirst().equals("--stdlib") ? "C0002" : "C0003";
                assertEquals(1, output.split(code, -1).length - 1, output);
                assertFalse(output.contains("C0018"), output);
            }
            String help = run(bundle, client, command, "--help");
            assertTrue(help.contains("--stdlib PATH"), help);
            assertTrue(help.contains("--no-stdlib"), help);
            assertFalse(help.contains("C0018"), help);
        }
        assertFalse(Files.exists(client.resolve("objects")));
        assertFalse(Files.exists(client.resolve("build")));
    }

    @Test void programsRunAfterTheirRelocatedCompilerAndLibrarySourcesBecomeUnavailable() throws Exception {
        Path staged = temporary.resolve("staged toolchain");
        copyBundle(staged);
        Path bundle = temporary.resolve("relocated toolchain");
        moveOwned(staged, bundle);
        Path client = clientWithPublicationFixture(bundle);
        Path invocationDirectory = externalInvocationDirectory();
        Path programs = Files.createDirectory(temporary.resolve("programs"));
        var executables = new ArrayList<Path>();
        var targets = new ArrayList<String>(List.of("native"));
        if (windows && System.getProperty("os.arch").equals("amd64")) targets.add("i686-pc-windows-msvc");
        for (String command : List.of("cloth", "shuttle")) for (String target : targets) {
            Path output = programs.resolve(command + "-" + target);
            var args = new ArrayList<String>();
            if (command.equals("shuttle")) args.addAll(List.of("build", "--project", client.toString(), "-o", output.toString()));
            else args.addAll(List.of(client.resolve("app/Main.co").toString(), output.toString()));
            if (!target.equals("native")) args.addAll(List.of("--target", target));
            String report = runExpectedFrom(0, bundle, invocationDirectory, temporary.resolve(command + "-" + target + ".log"), command, args.toArray(String[]::new));
            assertLocation(report, bundle);
            assertTrue(report.contains("user.dir = " + invocationDirectory), report);
            Path executable = windows ? output.resolveSibling(output.getFileName() + ".exe") : output;
            assertTrue(Files.size(executable) > 0);
            executables.add(executable);
        }

        // Only remove disposable, verified test-owned trees. Keep the separate native programs.
        removeOwnedTree(bundle);
        removeOwnedTree(client);
        assertFalse(Files.exists(bundle));
        assertFalse(Files.exists(client));
        for (Path executable : executables) {
            Path log = programs.resolve(executable.getFileName() + ".log");
            var builder = new ProcessBuilder(executable.toString()).directory(invocationDirectory.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            for (String name : List.of("JAVA_HOME", "JAVA_OPTS", "CLASSPATH", "CLOTH_HOME", "CLOTH_STDLIB")) builder.environment().remove(name);
            builder.environment().put("PATH", windows ? Path.of(System.getenv("SystemRoot"), "System32").toString() : "/usr/bin:/bin");
            var process = builder.start();
            process.getOutputStream().close();
            try { assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Native executable timed out"); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
            String output = Files.readString(log).replace("\r\n", "\n");
            assertEquals(0, process.exitValue(), output);
            assertEquals("Hello from bundled Cloth\n\nPEC: 0\n", output);
        }
    }

    @Test void bothPackagedCommandsEmitLibraryObjectsForEverySupportedTargetWithoutLinkerDiscovery() throws Exception {
        Path bundle = temporary.resolve("bundle");
        copyBundle(bundle);
        Path client = clientWithPublicationFixture(bundle);
        Path invocationDirectory = externalInvocationDirectory();
        List<String> targets = List.of("x86_64-pc-windows-msvc", "i686-pc-windows-msvc", "aarch64-pc-windows-msvc",
                "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl",
                "x86_64-apple-darwin", "aarch64-apple-darwin");
        for (String command : List.of("cloth", "shuttle")) for (String target : targets) {
            Path output = temporary.resolve(command + "-" + target + ".o");
            var args = new ArrayList<String>();
            if (command.equals("shuttle")) args.addAll(List.of("build", "--project", client.toString(), "-o", output.toString()));
            else args.addAll(List.of(client.resolve("app/Main.co").toString(), output.toString()));
            args.addAll(List.of("--target", target, "--emit=object", "--linker", temporary.resolve("missing-linker").toString()));
            runExpectedFrom(0, bundle, invocationDirectory, temporary.resolve(command + "-" + target + ".log"), command, args.toArray(String[]::new));
            assertTrue(Files.size(output) > 0, command + " / " + target);
        }
    }

    private Path clientWithPublicationFixture(Path bundle) throws IOException {
        Path example = Path.of(System.getProperty("cloth.test.workspace"), "examples/StandardLibrary");
        Path client = Files.createDirectory(temporary.resolve("client"));
        copyTree(example.resolve("app"), client.resolve("app"));
        Files.copy(example.resolve("build.toml"), client.resolve("build.toml"));
        copyTree(example.resolve("library/cloth"), bundle.resolve("stdlib/cloth"));
        Files.writeString(bundle.resolve("stdlib/cloth/example/Unreachable.co"), "@ deliberately invalid unreachable fixture\n");
        return client;
    }

    private Path externalInvocationDirectory() throws IOException {
        Path workspace = Path.of(System.getProperty("cloth.test.workspace")).toRealPath();
        Path invocation = Path.of(System.getProperty("user.home")).toRealPath();
        if (invocation.startsWith(workspace)) invocation = workspace.getRoot();
        assertFalse(invocation.startsWith(workspace));
        return invocation;
    }

    private void moveOwned(Path source, Path target) throws IOException {
        Path owned = temporary.toRealPath();
        assertTrue(source.toRealPath().startsWith(owned), source.toString());
        assertTrue(target.toAbsolutePath().normalize().startsWith(owned), target.toString());
        Files.move(source, target);
    }

    private void removeOwnedTree(Path tree) throws IOException {
        Path owned = temporary.toRealPath();
        Path resolved = tree.toRealPath();
        assertTrue(resolved.startsWith(owned) && !resolved.equals(owned), resolved.toString());
        List<Path> paths;
        try (var walk = Files.walk(tree)) { paths = walk.sorted(Comparator.reverseOrder()).toList(); }
        for (Path path : paths) Files.delete(path);
    }

    private void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path file : paths.toList()) {
                Path destination = target.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path clientWithLibraryFixture(Path bundle) throws IOException {
        Path sources = Files.createDirectories(bundle.resolve("stdlib/cloth/testing"));
        Files.writeString(sources.resolve("Answer.co"), "using cloth.testing.Helper : value\npub fn<int> answer() = value()\n");
        Files.writeString(sources.resolve("Helper.co"), "using cloth.testing.Answer : answer\npub fn<int> value() = 42\n");
        Files.writeString(sources.resolve("Unreachable.co"), "@ invalid but not imported\n");
        Path client = Files.createDirectory(temporary.resolve("client"));
        Files.writeString(client.resolve("Main.co"), "using cloth.testing.Answer : answer\nfn<int> main() = answer() - 42\n");
        Files.writeString(client.resolve("build.toml"), "[project]\nname = \"Client\"\nentryPoint = \"App.Main.main\"\n[modules]\nApp = \".\"\n");
        Files.writeString(client.resolve("module.toml"), "[module]\nname = \"App\"\n");
        return client;
    }

    private void assertLocation(String output, Path relocated) {
        assertTrue(output.contains("cloth.toolchain.home = " + relocated), output);
        assertTrue(output.contains("cloth.toolchain.layout = installed"), output);
        assertTrue(output.contains("java.home = " + relocated.resolve("runtime")), output);
        assertTrue(output.contains("java.specification.version = 25"), output);
    }

    private String run(Path toolchain, Path client, String command, String... args) throws Exception {
        return runExpected(0, toolchain, client, command, args);
    }

    private String runExpected(int expected, Path toolchain, Path client, String command, String... args) throws Exception {
        return runExpectedFrom(expected, toolchain, client, client.resolve(command + ".log"), command, args);
    }

    private String runExpectedFrom(int expected, Path toolchain, Path invocationDirectory, Path log, String command, String... args) throws Exception {
        boolean batch = windows && command.equals("cloth");
        Path script = toolchain.resolve("bin/" + command + (batch ? ".bat" : windows ? ".exe" : ""));
        var invocation = new ArrayList<String>();
        if (batch) invocation.addAll(List.of("cmd.exe", "/d", "/c"));
        invocation.add(script.toString());
        invocation.addAll(List.of(args));
        var builder = new ProcessBuilder(invocation).directory(invocationDirectory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", temporary.resolve("missing-jdk").toString());
        builder.environment().put("JAVA_OPTS", "-XshowSettings:properties -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \"-Djava.io.tmpdir=" + temporary + "\"");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        var process = builder.start();
        process.getOutputStream().close();
        try { assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Launcher timed out"); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        String output = Files.readString(log);
        assertEquals(expected, process.exitValue(), output);
        return output;
    }

    private void copyBundle(Path destination) throws IOException {
        try (var paths = Files.walk(installed)) {
            for (Path source : paths.toList()) {
                Path target = destination.resolve(installed.relativize(source));
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else if (source.toString().endsWith(".jar")) {
                    // Keep relocation tests small even with the native platform dependency jars.
                    try { Files.createLink(target, source); }
                    catch (IOException | UnsupportedOperationException failure) { Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES); }
                } else Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
}
