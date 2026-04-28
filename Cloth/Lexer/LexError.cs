using Cloth.Token;
using System;

namespace Cloth.Lexer {
    public class LexError : Exception {
        public LexErrorKind Kind { get; }
        public TokenSpan Span { get; }

        public LexError(LexErrorKind kind, TokenSpan span) : base(kind.ToString()) {
            Kind = kind;
            Span = span;
        }
    }

    public enum LexErrorKind {
        UnexpectedEof,
        IllegalControlChar,
        UnterminatedBlockComment,
        RadixWithoutDigits,
        EmptyExponent,
        UnterminatedCharLiteral,
        UnknownEscapeInChar,
        CharLiteralMultipleScalars,
        UnterminatedString,
        UnknownEscapeInString,
        IllegalCharacter
    }
}
