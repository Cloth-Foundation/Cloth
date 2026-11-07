package cloth.api

import java.nio.file.Path

/** A successful build's actual absolute artifact path, including target filename rules. */
data class CompilationResult(val artifact: Path)
