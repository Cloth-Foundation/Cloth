package cloth;

import cloth.frontend.file.ClothObject;
import cloth.frontend.lexer.Lexer;

import java.nio.file.Path;

public class Main {

	static void main(String[] args) {
		var lexer = new Lexer(ClothObject.of(Path.of("F:/Cloth"), Path.of("F:/Cloth/Test.co"))).scan();
		for (var token : lexer.getTokens()) {
			System.out.println(token);
		}
	}

}
