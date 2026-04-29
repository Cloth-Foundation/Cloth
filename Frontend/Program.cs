using FrontEnd.File;
using FrontEnd.Lexer;

var file = new ClothFile(@"F:/Cloth/Tests/Project/src/my/project/Main.co", "Main.co");
var tokens = new Lexer(file).LexAll();

for (var i = 0; i < tokens.Count; i++) Console.WriteLine(i + 1 + ": " + tokens[i]);