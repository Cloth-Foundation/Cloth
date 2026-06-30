package cloth.frontend.arguments;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record ArgumentFlag(@NotNull String flag, @Nullable ArgumentAlias aliases, @NotNull String label, @NotNull String description, @Nullable String value) {
}
