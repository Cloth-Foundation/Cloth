package cloth;

import cloth.frontend.arguments.Arguments;
import cloth.frontend.file.ClothObject;
import cloth.frontend.lexer.Lexer;
import lombok.Getter;

import java.nio.file.Path;

public class ClothProgram {

	@Getter
	private static Arguments arguments;

	static void main(String[] args) {
		arguments = new Arguments(args);

		var lexer = new Lexer(ClothObject.of(Path.of("F:/Cloth"), Path.of("F:/Cloth/Test.co"))).scan();
		for (var token : lexer.getTokens()) {
			System.out.println(token);
		}
	}

}
