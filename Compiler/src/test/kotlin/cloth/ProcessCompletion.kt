package cloth

/** Process Exit Code report with native text-mode line endings. */
internal fun processCompletion(status: Int = 0): String = "\nPEC: $status\n".replace("\n", System.lineSeparator())
