// Copyright (c) 2026.The Cloth contributors.
// 
// CirPrinter.cs is part of the Cloth Compiler.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

using System.Text;

namespace Compiler.CIR;

// Produces a human-readable text dump of a CirModule for debugging.
public static class CirPrinter {
	public static string Print(CirModule module) {
		var sb = new StringBuilder();
		sb.AppendLine("CirModule {");

		sb.AppendLine("  Types:");
		foreach (var type in module.Types)
			PrintTypeDecl(sb, type, indent: 4);

		sb.AppendLine("  Functions:");
		foreach (var fn in module.Functions)
			PrintFunction(sb, fn, indent: 4);

		sb.Append('}');
		return sb.ToString();
	}

	// -------------------------------------------------------------------------
	// Type declarations
	// -------------------------------------------------------------------------

	private static void PrintTypeDecl(StringBuilder sb, CirTypeDecl decl, int indent) {
		var pad = new string(' ', indent);
		switch (decl) {
			case CirTypeDecl.Class c:
				sb.Append($"{pad}class {c.FullyQualifiedName}");
				if (c.BaseClass != null) sb.Append($" extends {c.BaseClass}");
				if (c.Interfaces.Count > 0) sb.Append($" is {string.Join(", ", c.Interfaces)}");
				if (c.IsAbstract) sb.Append(" [abstract]");
				if (c.IsConst) sb.Append(" [const]");
				sb.AppendLine(" {");
				foreach (var f in c.Fields)
					sb.AppendLine($"{pad}  field {f.Name}: {PrintType(f.Type)}{(f.IsConst ? " [const]" : "")}{(f.IsAtomic ? " [atomic]" : "")}{(f.Initializer != null ? $" = {PrintExpr(f.Initializer)}" : "")}");
				sb.AppendLine($"{pad}}}");
				break;

			case CirTypeDecl.Struct s:
				sb.AppendLine($"{pad}struct {s.FullyQualifiedName} {{");
				foreach (var f in s.Fields)
					sb.AppendLine($"{pad}  field {f.Name}: {PrintType(f.Type)}");
				sb.AppendLine($"{pad}}}");
				break;

			case CirTypeDecl.Enum e:
				sb.AppendLine($"{pad}enum {e.FullyQualifiedName} {{");
				foreach (var c in e.Cases) {
					var disc = c.Discriminant != null ? $" = {PrintExpr(c.Discriminant)}" : "";
					var payload = c.Payload.Count > 0 ? $"({string.Join(", ", c.Payload.Select(PrintType))})" : "";
					sb.AppendLine($"{pad}  case {c.Name}{payload}{disc}");
				}

				sb.AppendLine($"{pad}}}");
				break;

			case CirTypeDecl.Interface i:
				sb.AppendLine($"{pad}interface {i.FullyQualifiedName}");
				break;

			case CirTypeDecl.Trait t:
				sb.AppendLine($"{pad}trait {t.FullyQualifiedName}");
				break;
		}
	}

	// -------------------------------------------------------------------------
	// Functions
	// -------------------------------------------------------------------------

	private static void PrintFunction(StringBuilder sb, CirFunction fn, int indent) {
		var pad = new string(' ', indent);
		var kind = fn.Kind switch {
			CirFunctionKind.Constructor => "ctor",
			CirFunctionKind.Destructor => "dtor",
			CirFunctionKind.Method => "fn",
			CirFunctionKind.Fragment => "fragment",
			CirFunctionKind.StaticMethod => "static fn",
			_ => "fn"
		};
		var paramStr = string.Join(", ", fn.Parameters.Select(p => $"{p.Name}: {PrintType(p.Type)}"));
		var ret = PrintType(fn.ReturnType);
		var extern_ = fn.IsExtern ? " [extern]" : "";

		sb.AppendLine($"{pad}{kind} {fn.MangledName}({paramStr}) -> {ret}{extern_} {{");
		foreach (var stmt in fn.Body)
			PrintStmt(sb, stmt, indent + 2);
		sb.AppendLine($"{pad}}}");
	}

	// -------------------------------------------------------------------------
	// Statements
	// -------------------------------------------------------------------------

	private static void PrintStmt(StringBuilder sb, CirStmt stmt, int indent) {
		var pad = new string(' ', indent);
		switch (stmt) {
			case CirStmt.LocalDecl d:
				var typeStr = d.Type != null ? $": {PrintType(d.Type)}" : "";
				var init = d.Init != null ? $" = {PrintExpr(d.Init)}" : "";
				sb.AppendLine($"{pad}let {d.Name}{typeStr}{init}");
				break;

			case CirStmt.TupleDecl t:
				var bindings = string.Join(", ", t.Bindings.Select(b => $"{b.Name}: {PrintType(b.Type)}"));
				sb.AppendLine($"{pad}let ({bindings}) = {PrintExpr(t.Init)}");
				break;

			case CirStmt.Assign a:
				sb.AppendLine($"{pad}{PrintExpr(a.Target)} {PrintAssignOp(a.Op)} {PrintExpr(a.Value)}");
				break;

			case CirStmt.Expr e:
				sb.AppendLine($"{pad}{PrintExpr(e.Expression)}");
				break;

			case CirStmt.Discard d:
				sb.AppendLine($"{pad}_ = {PrintExpr(d.Expression)}");
				break;

			case CirStmt.Return r:
				sb.AppendLine(r.Value != null ? $"{pad}return {PrintExpr(r.Value)}" : $"{pad}return");
				break;

			case CirStmt.If i:
				sb.AppendLine($"{pad}if {PrintExpr(i.Condition)} {{");
				foreach (var s in i.Then) PrintStmt(sb, s, indent + 2);
				foreach (var (cond, body) in i.ElseIfs) {
					sb.AppendLine($"{pad}}} else if {PrintExpr(cond)} {{");
					foreach (var s in body) PrintStmt(sb, s, indent + 2);
				}

				if (i.Else != null) {
					sb.AppendLine($"{pad}}} else {{");
					foreach (var s in i.Else) PrintStmt(sb, s, indent + 2);
				}

				sb.AppendLine($"{pad}}}");
				break;

			case CirStmt.While w:
				sb.AppendLine($"{pad}while {PrintExpr(w.Condition)} {{");
				foreach (var s in w.Body) PrintStmt(sb, s, indent + 2);
				sb.AppendLine($"{pad}}}");
				break;

			case CirStmt.DoWhile d:
				sb.AppendLine($"{pad}do {{");
				foreach (var s in d.Body) PrintStmt(sb, s, indent + 2);
				sb.AppendLine($"{pad}}} while {PrintExpr(d.Condition)}");
				break;

			case CirStmt.For f:
				sb.Append($"{pad}for (");
				var forSb = new StringBuilder();
				PrintStmt(forSb, f.Init, 0);
				sb.Append(forSb.ToString().Trim());
				sb.AppendLine($"; {PrintExpr(f.Condition)}; {PrintExpr(f.Iterator)}) {{");
				foreach (var s in f.Body) PrintStmt(sb, s, indent + 2);
				sb.AppendLine($"{pad}}}");
				break;

			case CirStmt.ForIn fi:
				sb.AppendLine($"{pad}for ({fi.ElementName}: {PrintType(fi.ElementType)} in {PrintExpr(fi.Iterable)}) {{");
				foreach (var s in fi.Body) PrintStmt(sb, s, indent + 2);
				sb.AppendLine($"{pad}}}");
				break;

			case CirStmt.Switch sw:
				sb.AppendLine($"{pad}switch {PrintExpr(sw.Subject)} {{");
				foreach (var c in sw.Cases) {
					var pattern = c.Pattern != null ? $"case {PrintExpr(c.Pattern)}" : "default";
					sb.AppendLine($"{pad}  {pattern}:");
					foreach (var s in c.Body) PrintStmt(sb, s, indent + 4);
				}

				sb.AppendLine($"{pad}}}");
				break;

			case CirStmt.Break:
				sb.AppendLine($"{pad}break");
				break;

			case CirStmt.Continue:
				sb.AppendLine($"{pad}continue");
				break;

			case CirStmt.Throw t:
				sb.AppendLine($"{pad}throw {PrintExpr(t.Expression)}");
				break;

			case CirStmt.Delete d:
				sb.AppendLine($"{pad}delete {PrintExpr(d.Expression)}");
				break;

			case CirStmt.Block b:
				sb.AppendLine($"{pad}{{");
				foreach (var s in b.Body) PrintStmt(sb, s, indent + 2);
				sb.AppendLine($"{pad}}}");
				break;
		}
	}

	// -------------------------------------------------------------------------
	// Expressions (returns inline string)
	// -------------------------------------------------------------------------

	private static string PrintExpr(CirExpr expr) => expr switch {
		CirExpr.IntLit i => i.Value,
		CirExpr.FloatLit f => f.Value,
		CirExpr.BoolLit b => b.Value ? "true" : "false",
		CirExpr.CharLit c => $"'{c.Value}'",
		CirExpr.StrLit s => $"\"{s.Value}\"",
		CirExpr.NullLit => "null",
		CirExpr.Local l => l.Name,
		CirExpr.ThisPtr => "this",

		CirExpr.Binary b => $"({PrintExpr(b.Left)} {PrintBinOp(b.Op)} {PrintExpr(b.Right)})",
		CirExpr.Unary u => $"({PrintUnOp(u.Op)} {PrintExpr(u.Operand)})",

		CirExpr.FieldAccess fa => $"{PrintExpr(fa.Target)}->{fa.FieldName}",
		CirExpr.StaticAccess sa => $"{sa.TypeFqn}::{sa.MemberName}",
		CirExpr.Index i => $"{PrintExpr(i.Target)}[{PrintExpr(i.Idx)}]",

		CirExpr.Call c => $"{c.MangledName}({string.Join(", ", c.Args.Select(PrintExpr))})",
		CirExpr.IndirectCall ic => $"(*{PrintExpr(ic.Callee)})({string.Join(", ", ic.Args.Select(PrintExpr))})",
		CirExpr.Alloc a => $"alloc {PrintType(a.Type)} via {a.CtorMangledName}({string.Join(", ", a.Args.Select(PrintExpr))})",

		CirExpr.Cast c => $"({(c.IsSafe ? "safe " : "")}cast<{PrintType(c.TargetType)}> {PrintExpr(c.Value)})",
		CirExpr.TypeCheck t => $"({PrintExpr(t.Value)} is {PrintType(t.TargetType)})",
		CirExpr.Ternary t => $"({PrintExpr(t.Condition)} ? {PrintExpr(t.Then)} : {PrintExpr(t.Else)})",
		CirExpr.NullCoalesce n => $"({PrintExpr(n.Left)} ?? {PrintExpr(n.Right)})",
		CirExpr.TupleLit t => $"({string.Join(", ", t.Elements.Select(PrintExpr))})",
		CirExpr.Range r => $"{PrintExpr(r.Start)}..{PrintExpr(r.End)}",

		_ => $"<{expr.GetType().Name}>"
	};

	// -------------------------------------------------------------------------
	// Types
	// -------------------------------------------------------------------------

	private static string PrintType(CirType type) => type switch {
		CirType.Named n => n.FullyQualifiedName,
		CirType.Ptr p => $"*{PrintType(p.Inner)}",
		CirType.Nullable n => $"{PrintType(n.Inner)}?",
		CirType.Array a => $"{PrintType(a.Element)}[]",
		CirType.Tuple t => $"({string.Join(", ", t.Elements.Select(PrintType))})",
		CirType.Generic g => $"{g.FullyQualifiedName}<{string.Join(", ", g.Args.Select(PrintType))}>",
		CirType.Void => "void",
		CirType.Any => "any",
		_ => $"<{type.GetType().Name}>"
	};

	// -------------------------------------------------------------------------
	// Operators
	// -------------------------------------------------------------------------

	private static string PrintBinOp(CirBinOp op) => op switch {
		CirBinOp.Add => "+", CirBinOp.Sub => "-", CirBinOp.Mul => "*",
		CirBinOp.Div => "/", CirBinOp.Rem => "%",
		CirBinOp.And => "&&", CirBinOp.Or => "||",
		CirBinOp.BitAnd => "&", CirBinOp.BitOr => "|", CirBinOp.BitXor => "^",
		CirBinOp.Shl => "<<", CirBinOp.Shr => ">>",
		CirBinOp.Eq => "==", CirBinOp.NotEq => "!=",
		CirBinOp.Lt => "<", CirBinOp.LtEq => "<=",
		CirBinOp.Gt => ">", CirBinOp.GtEq => ">=",
		CirBinOp.In => "in",
		_ => "?"
	};

	private static string PrintUnOp(CirUnOp op) => op switch {
		CirUnOp.Neg => "-", CirUnOp.Not => "!", CirUnOp.BitNot => "~",
		CirUnOp.PreInc => "++", CirUnOp.PreDec => "--",
		CirUnOp.PostInc => "++", CirUnOp.PostDec => "--",
		CirUnOp.Await => "await",
		_ => "?"
	};

	private static string PrintAssignOp(CirAssignOp op) => op switch {
		CirAssignOp.Assign => "=",
		CirAssignOp.AddAssign => "+=", CirAssignOp.SubAssign => "-=",
		CirAssignOp.MulAssign => "*=", CirAssignOp.DivAssign => "/=",
		CirAssignOp.RemAssign => "%=", CirAssignOp.AndAssign => "&=",
		CirAssignOp.OrAssign => "|=", CirAssignOp.XorAssign => "^=",
		_ => "="
	};
}