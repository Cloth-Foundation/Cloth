package cloth.frontend.arguments;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Argument {

	@Getter
	private final String key;

	@Getter
	private final ArgumentAlias alias;

	@Getter
	private final ArgumentFlags flags;

	@Getter
	private final String label;

	@Getter
	private final String description;

	@Getter
	private String value;

	public Argument(@NotNull String key, @Nullable ArgumentAlias aliases, @Nullable ArgumentFlags flags, @NotNull String label, @NotNull String description, @Nullable String value) {
		this.key = key;
		this.alias = aliases;
		this.flags = flags;
		this.label = label;
		this.description = description;
		this.value = value;
	}

	public Argument setValue(@NotNull String value) {
		this.value = value;
		return this;
	}

}
