// Copyright (c) 2026.The Cloth contributors.
// 
// TypeInference.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Globalization;
using FrontEnd.Parser.AST.Expressions;
using FrontEnd.Parser.AST.Type;
using FrontEnd.Token;

namespace Compiler.Semantics;

// Inference + canonicalization for primitive Cloth types.
// Aliases collapse to a canonical short name (e.g. int -> i32, float -> f64).
// Literal expressions infer to the smallest signed integer that fits, and to f64 for floats.
public static class TypeInference {
	// Canonical primitive type names. Anything outside this set is treated as a user-defined type.
	public static readonly HashSet<string> Canonical = new() {
		"i8", "i16", "i32", "i64",
		"u8", "u16", "u32", "u64",
		"f32", "f64",
		"bool", "char", "byte", "bit", "string", "void", "any"
	};

	// Source name -> canonical name. Names not in this map pass through unchanged.
	public static readonly Dictionary<string, string> Aliases = new() {
		{ "int", "i32" },
		{ "uint", "u32" },
		{ "long", "i64" },
		{ "short", "i16" },
		{ "float", "f64" },
		{ "double", "f64" },
		{ "real", "f64" },
		{ "unsigned", "u32" }
		// 'byte', 'char', 'bool', 'bit', 'string' are already canonical.
	};

	public static string Canonicalize(string name) =>
		Aliases.TryGetValue(name, out var c) ? c : name;

	public static bool IsKnownPrimitive(string canonicalName) =>
		Canonical.Contains(canonicalName);

	// True if a value of type `from` can be implicitly converted to type `to` without losing information.
	// Same-signedness integer widening (i8 → i32), unsigned-to-strictly-wider-signed (u8 → i16),
	// f32 → f64, and integer-to-float when the integer fits in the float's mantissa are lossless.
	public static bool IsLosslessPromotion(string from, string to) {
		if (from == to) return true;

		if (IntBits(from) is int fromBits && IntBits(to) is int toBits) {
			var fromSigned = from[0] == 'i';
			var toSigned = to[0] == 'i';

			if (fromSigned == toSigned) return toBits >= fromBits; // i8 → i32, u8 → u32
			if (!fromSigned && toSigned) return toBits > fromBits; // u8 → i16 (strictly wider)
			return false; // signed → unsigned: never lossless
		}

		if (from == "f32" && to == "f64") return true;

		// Integer → float when the integer fits exactly in the float's significand.
		// f64 has 53 mantissa bits, f32 has 24. Any iN/uN where N ≤ those bounds is lossless.
		if (IntBits(from) is int srcBits) {
			if (to == "f64" && srcBits <= 53) return true;
			if (to == "f32" && srcBits <= 24) return true;
		}

		return false;
	}

	private static int? IntBits(string canonical) => canonical switch {
		"i8" or "u8" => 8,
		"i16" or "u16" => 16,
		"i32" or "u32" => 32,
		"i64" or "u64" => 64,
		_ => null
	};

	// Infer the canonical type of an Expression. Returns null when inference is not possible
	// (e.g. user-typed identifiers, calls, complex expressions). Callers decide whether that's an error.
	public static BaseType.Named? Infer(Expression expr) => expr switch {
		Expression.Literal { Value: var v } => InferLiteral(v),
		_ => null
	};

	private static BaseType.Named? InferLiteral(Literal lit) => lit switch {
		Literal.Int i => new BaseType.Named(SmallestSignedFit(i.Value)),
		Literal.Float _ => new BaseType.Named("f64"),
		Literal.Bool _ => new BaseType.Named("bool"),
		Literal.Char _ => new BaseType.Named("char"),
		Literal.Str _ => new BaseType.Named("string"),
		Literal.Bit _ => new BaseType.Named("bit"),
		_ => null
	};

	// Smallest signed integer type whose value range covers `text`. Falls back to i64 on overflow.
	// Accepts decimal, 0x… hex, 0b… binary, 0o… octal. Underscores in literals are stripped.
	public static string SmallestSignedFit(string text) {
		if (!TryParseSignedInt(text, out var value)) return "i64";

		if (value >= sbyte.MinValue && value <= sbyte.MaxValue) return "i8";
		if (value >= short.MinValue && value <= short.MaxValue) return "i16";
		if (value >= int.MinValue && value <= int.MaxValue) return "i32";
		return "i64";
	}

	private static bool TryParseSignedInt(string text, out long result) {
		result = 0;
		var clean = text.Replace("_", "");
		var negative = false;
		if (clean.StartsWith('-')) {
			negative = true;
			clean = clean[1..];
		}
		else if (clean.StartsWith('+')) clean = clean[1..];

		bool ok;
		if (clean.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
			ok = long.TryParse(clean[2..], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out result);
		else if (clean.StartsWith("0b", StringComparison.OrdinalIgnoreCase))
			ok = TryParseBinary(clean[2..], out result);
		else if (clean.StartsWith("0o", StringComparison.OrdinalIgnoreCase))
			ok = TryParseOctal(clean[2..], out result);
		else
			ok = long.TryParse(clean, NumberStyles.Integer, CultureInfo.InvariantCulture, out result);

		if (ok && negative) result = -result;
		return ok;
	}

	private static bool TryParseBinary(string s, out long result) {
		result = 0;
		if (s.Length == 0 || s.Length > 64) return false;
		foreach (var c in s) {
			if (c is not ('0' or '1')) return false;
			result = (result << 1) + (c - '0');
		}

		return true;
	}

	private static bool TryParseOctal(string s, out long result) {
		result = 0;
		if (s.Length == 0) return false;
		foreach (var c in s) {
			if (c is < '0' or > '7') return false;
			result = (result << 3) + (c - '0');
		}

		return true;
	}
}