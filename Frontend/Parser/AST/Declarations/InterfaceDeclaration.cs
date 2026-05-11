// Copyright (c) 2026.The Cloth contributors.
// 
// InterfaceDeclaration.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using FrontEnd.Token;

namespace FrontEnd.Parser.AST.Declarations;

// `Extends` is the (possibly empty) list of parent interface names from the `: Bar, Baz`
// clause on the interface declaration. Stored as raw identifiers; the registry resolves
// each entry to a fully-qualified interface FQN at registration time.
public readonly record struct InterfaceDeclaration(Visibility Visibility, string Name, List<string> Extends, List<MemberDeclaration> Members, TokenSpan Span);