package loom.compiler.ast;

public class TypeNode {
	public final boolean isArray;
	public final String baseType;
	public final boolean isNullable;

	public TypeNode(boolean isArray, String baseType) {
		this(isArray, baseType, false);
	}

	public TypeNode(boolean isArray, String baseType, boolean isNullable) {
		this.isArray = isArray;
		this.baseType = baseType;
		this.isNullable = isNullable;
	}

	@Override
	public String toString() {
		String result = (isArray ? "[]" : "") + baseType;
		if (isNullable) {
			result += "?";
		}
		return result;
	}
}
