package loom.compiler.ast;

import loom.compiler.token.TokenSpan;

public interface Node {

	<T> T accept (NodeVisitor<T> visitor);

	TokenSpan getSpan ();

}
