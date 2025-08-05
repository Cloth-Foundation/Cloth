package loom.compiler;

import loom.compiler.ast.ASTPrinter;
import loom.compiler.ast.Stmt;
import loom.compiler.diagnostics.ErrorReporter;
import loom.compiler.lexer.Lexer;
import loom.compiler.parser.Parser;
import loom.compiler.semantic.SemanticAnalyzer;
import loom.compiler.token.Token;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {

	public static void main (String[] args) {

		if (args.length <= 0) {
			System.err.println("Usage: loom <flags> <source-file>");
			return;
		}

		String filePath = args[0];
		Path path = Path.of(filePath);
		String fileName = path.getFileName().toString();

		String source;
		try {
			source = Files.readString(path);
		} catch (IOException e) {
			System.err.println("Failed to read source file: " + e.getMessage());
			return;
		}

		ErrorReporter reporter = new ErrorReporter();
		reporter.setGlobalSourceCode(source);
		// Lexing
		Lexer lexer = new Lexer(source, fileName, reporter);
		List<Token> tokens = lexer.tokenize();

		if (reporter.hasErrors()) {
			reporter.printAll();
			return;
		}
		
		Parser parser = new Parser(tokens, reporter);
		List<Stmt> program = parser.parse();

		if (reporter.hasErrors()) {
			reporter.printAll();
			return;
		}

		// Perform two-pass semantic analysis
		// Pass the root directory as the base path so modules can be found
		String basePath = ".";
		SemanticAnalyzer analyzer = new SemanticAnalyzer(reporter, basePath);
		
		System.out.println("=== Two-Pass Compilation ===");
		System.out.println("Pass 1: Collecting declarations...");
		analyzer.analyze(program);

		if (reporter.hasErrors()) {
			reporter.printAll();
			return;
		}

		System.out.println("Pass 2: Semantic analysis completed successfully!");
		System.out.println("=== Parsed AST ===");
		ASTPrinter printer = new ASTPrinter();
		for (Stmt stmt : program) {
			System.out.println(stmt.accept(printer));
		}
		
		// Print symbol table for debugging
		System.out.println("\n=== Symbol Table ===");
		System.out.println(analyzer.getSymbolTable());
	}
}

