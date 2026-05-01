// Copyright (c) 2026.The Cloth contributors.
// 
// Keywords.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Token;

public enum Keyword {
	True,
	False,
	Null,
	NaN,

	If,
	Else,
	Switch,
	Case,
	Default,
	For,
	While,
	Do,
	Break,
	Continue,
	Yield,
	Return,
	Throw,
	Guard,
	Defer,
	Await,

	And,
	Or,
	Is,
	In,
	As,
	Maybe,

	Public,
	Private,
	Internal,
	Static,
	Const,
	Let,
	Getter,
	Setter,
	Atomic,
	Abstract,
	Prototype,

	Module,
	Import,
	Class,
	Struct,
	Enum,
	Interface,
	Trait,
	Type,
	Func,
	New,
	This,
	Super,

	Bit,
	Bool,
	Char,
	Byte,

	I8,
	I16,
	I32,
	I64,
	U8,
	U16,
	U32,
	U64,
	F32,
	F64,
	Float,
	Double,
	Real,
	Long,
	Short,
	Int,
	Uint,
	Unsigned,
	Void,
	Any,
	String
}

public enum MetaKeyword {
	Alignof,
	Default,
	Length,
	Max,
	Memspace,
	Min,
	Sizeof,
	ToBits,
	ToBytes,
	ToString,
	Typeof
}

public enum TraitKeyword {
	Override,
	Implementation,
	Deprecated
}

public static class Keywords {
	public static readonly string[] KeywordStrings = Enum.GetNames(typeof(Keyword));

	public static readonly string[] MetaKeywordStrings = Enum.GetNames(typeof(MetaKeyword));

	public static readonly string[] TraitKeywordStrings = Enum.GetNames(typeof(TraitKeyword));

	public static string GetKeywordString(Keyword? keyword) {
		if (keyword == null) {
			return "[UNKNOWN]";
		}

		return keyword switch {
			Keyword.True => "true",
			Keyword.False => "false",
			Keyword.Null => "null",
			Keyword.NaN => "NaN",
			Keyword.If => "if",
			Keyword.Else => "else",
			Keyword.Switch => "switch",
			Keyword.Case => "case",
			Keyword.Default => "default",
			Keyword.For => "for",
			Keyword.While => "while",
			Keyword.Do => "do",
			Keyword.Break => "break",
			Keyword.Continue => "continue",
			Keyword.Yield => "yield",
			Keyword.Return => "return",
			Keyword.Throw => "throw",
			Keyword.Guard => "guard",
			Keyword.Defer => "defer",
			Keyword.Await => "await",
			Keyword.And => "and",
			Keyword.Or => "or",
			Keyword.Is => "is",
			Keyword.In => "in",
			Keyword.As => "as",
			Keyword.Maybe => "maybe",
			Keyword.Public => "public",
			Keyword.Private => "private",
			Keyword.Internal => "internal",
			Keyword.Static => "static",
			Keyword.Const => "const",
			Keyword.Let => "let",
			Keyword.Getter => "getter",
			Keyword.Setter => "setter",
			Keyword.Atomic => "atomic",
			Keyword.Abstract => "abstract",
			Keyword.Prototype => "prototype",
			Keyword.Module => "module",
			Keyword.Import => "import",
			Keyword.Class => "class",
			Keyword.Struct => "struct",
			Keyword.Enum => "enum",
			Keyword.Interface => "interface",
			Keyword.Trait => "trait",
			Keyword.Type => "type",
			Keyword.Func => "func",
			Keyword.New => "new",
			Keyword.This => "this",
			Keyword.Super => "super",
			Keyword.Bit => "bit",
			Keyword.Bool => "bool",
			Keyword.Char => "char",
			Keyword.Byte => "byte",
			Keyword.I8 => "i8",
			Keyword.I16 => "i16",
			Keyword.I32 => "i32",
			Keyword.I64 => "i64",
			Keyword.U8 => "u8",
			Keyword.U16 => "u16",
			Keyword.U32 => "u32",
			Keyword.U64 => "u64",
			Keyword.F32 => "f32",
			Keyword.F64 => "f64",
			Keyword.Float => "float",
			Keyword.Double => "double",
			Keyword.Real => "real",
			Keyword.Long => "long",
			Keyword.Short => "short",
			Keyword.Int => "int",
			Keyword.Uint => "uint",
			Keyword.Unsigned => "unsigned",
			Keyword.Void => "void",
			Keyword.Any => "any",
			Keyword.String => "string",
			_ => throw new NotImplementedException()
		};
	}

	public static string GetMetaKeywordString(MetaKeyword metaKeyword) {
		return metaKeyword switch {
			MetaKeyword.Alignof => "ALIGN",
			MetaKeyword.Default => "DEFAULT",
			MetaKeyword.Length => "LENGTH",
			MetaKeyword.Max => "MAX",
			MetaKeyword.Memspace => "MEMSPACE",
			MetaKeyword.Min => "MIN",
			MetaKeyword.Sizeof => "SIZE",
			MetaKeyword.ToBits => "BITS",
			MetaKeyword.ToBytes => "BYTES",
			MetaKeyword.ToString => "STRING",
			MetaKeyword.Typeof => "TYPE",
			_ => throw new NotImplementedException()
		};
	}

	public static string GetTraitKeywordString(TraitKeyword traitKeyword) {
		return traitKeyword switch {
			TraitKeyword.Override => "Override",
			TraitKeyword.Implementation => "Implementation",
			TraitKeyword.Deprecated => "Deprecated",
			_ => throw new NotImplementedException()
		};
	}

	public static Keyword? GetKeywordFromLexeme(string lexeme) {
		return lexeme switch {
			"true" => Keyword.True,
			"false" => Keyword.False,
			"null" => Keyword.Null,
			"NaN" => Keyword.NaN,
			"if" => Keyword.If,
			"else" => Keyword.Else,
			"switch" => Keyword.Switch,
			"case" => Keyword.Case,
			"default" => Keyword.Default,
			"for" => Keyword.For,
			"while" => Keyword.While,
			"do" => Keyword.Do,
			"break" => Keyword.Break,
			"continue" => Keyword.Continue,
			"yield" => Keyword.Yield,
			"return" => Keyword.Return,
			"throw" => Keyword.Throw,
			"guard" => Keyword.Guard,
			"defer" => Keyword.Defer,
			"await" => Keyword.Await,
			"and" => Keyword.And,
			"or" => Keyword.Or,
			"is" => Keyword.Is,
			"in" => Keyword.In,
			"as" => Keyword.As,
			"maybe" => Keyword.Maybe,
			"public" => Keyword.Public,
			"private" => Keyword.Private,
			"internal" => Keyword.Internal,
			"static" => Keyword.Static,
			"const" => Keyword.Const,
			"let" => Keyword.Let,
			"getter" => Keyword.Getter,
			"setter" => Keyword.Setter,
			"atomic" => Keyword.Atomic,
			"abstract" => Keyword.Abstract,
			"prototype" => Keyword.Prototype,
			"module" => Keyword.Module,
			"import" => Keyword.Import,
			"class" => Keyword.Class,
			"struct" => Keyword.Struct,
			"enum" => Keyword.Enum,
			"interface" => Keyword.Interface,
			"trait" => Keyword.Trait,
			"type" => Keyword.Type,
			"func" => Keyword.Func,
			"new" => Keyword.New,
			"this" => Keyword.This,
			"super" => Keyword.Super,
			"bit" => Keyword.Bit,
			"bool" => Keyword.Bool,
			"char" => Keyword.Char,
			"byte" => Keyword.Byte,
			"i8" => Keyword.I8,
			"i16" => Keyword.I16,
			"i32" => Keyword.I32,
			"i64" => Keyword.I64,
			"u8" => Keyword.U8,
			"u16" => Keyword.U16,
			"u32" => Keyword.U32,
			"u64" => Keyword.U64,
			"f32" => Keyword.F32,
			"f64" => Keyword.F64,
			"float" => Keyword.Float,
			"double" => Keyword.Double,
			"real" => Keyword.Real,
			"long" => Keyword.Long,
			"short" => Keyword.Short,
			"int" => Keyword.Int,
			"uint" => Keyword.Uint,
			"unsigned" => Keyword.Unsigned,
			"void" => Keyword.Void,
			"any" => Keyword.Any,
			"string" => Keyword.String,
			_ => null
		};
	}

	public static MetaKeyword? GetMetaKeywordFromLexeme(string lexeme) {
		return lexeme switch {
			"ALIGN" => MetaKeyword.Alignof,
			"DEFAULT" => MetaKeyword.Default,
			"LENGTH" => MetaKeyword.Length,
			"MAX" => MetaKeyword.Max,
			"MEMSPACE" => MetaKeyword.Memspace,
			"MIN" => MetaKeyword.Min,
			"SIZE" => MetaKeyword.Sizeof,
			"BITS" => MetaKeyword.ToBits,
			"BYTES" => MetaKeyword.ToBytes,
			"STRING" => MetaKeyword.ToString,
			"TYPE" => MetaKeyword.Typeof,
			_ => null
		};
	}
}