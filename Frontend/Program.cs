// Copyright (c) 2026.The Cloth contributors.
// 
// Program.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.File;
using FrontEnd.Lexer;
using FrontEnd.Parser;

var file = new ClothFile(@"F:/Cloth/Tests/Project/src/my/project/Main.co", "Main.co");
var lexer = new Lexer(file);
var parser = new Parser(lexer);
parser.Parse();