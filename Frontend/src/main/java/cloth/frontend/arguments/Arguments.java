package cloth.frontend.arguments;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

public final class Arguments {

	private static final List<ArgumentsList> ARGUMENTS;

	@Getter
	private final String[] args;

	public Arguments(String[] args) {
		this.args = args;
	}

	static {
		ARGUMENTS = Arrays.stream(ArgumentsList.values()).toList();
	}

}
