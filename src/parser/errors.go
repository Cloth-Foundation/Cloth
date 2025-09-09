package parser

import (
	"compiler/src/tokens"
	"fmt"
)

type ParseError struct {
	Message string
	Span    tokens.TokenSpan
	Hint    string
}

func (e ParseError) Error() string { return fmt.Sprintf("%s at %s", e.Message, e.Span.String()) }
