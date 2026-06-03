package cloth.frontend.token;

import org.jetbrains.annotations.NotNull;

public record SourceLocation(Position startPosition, Position endPosition) {

	@Override
	public @NotNull String toString () {
		return  String.format("%s-%s", startPosition, endPosition);
	}

}
