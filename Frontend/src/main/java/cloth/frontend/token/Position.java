package cloth.frontend.token;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;

public record Position(int line, int column) implements Comparable<Position> {

	@Override
	public int compareTo (@NonNull Position position) {
		return Integer.compare(line, position.line);
	}

	@Override
	public @NotNull String toString () {
		return  String.format("%d:%d", line, column);
	}

}
