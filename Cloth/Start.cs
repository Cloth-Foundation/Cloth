using Cloth.File;
using System;
using System.Collections.Generic;

namespace Cloth {
	public class Start {

		public static void Main (string[] args) {
			ClothFile file = new ClothFile("F:\\Cloth\\Cloth\\HelloWorld.co", "HelloWorld.co");
			List<Token.Token> tokens = new Lexer.Lexer(file).LexAll();

			for (int i = 0; i < tokens.Count; i++) {
				Console.WriteLine((i + 1) + ": " + tokens[i]);
			}
		}

	}
}
