package loom.compiler.semantic;

import loom.compiler.parser.ScopeManager;

/**
 * Represents a declared symbol (variable, function, class, etc.)
 */
public class Symbol {

	public enum Kind {
		VARIABLE, FUNCTION, CLASS, ENUM, STRUCT, FIELD, METHOD
	}

	private final String name;
	private final Kind kind;
	private final String type;
	private final boolean mutable;
	private final Object value;
	private final int address; // memory address
	private final ScopeManager.Scope accessLevel; // Access control level

	public Symbol (String name, Kind kind, String type, boolean mutable, Object value, int address) {
		this(name, kind, type, mutable, value, address, ScopeManager.Scope.DEFAULT);
	}

	public Symbol (String name, Kind kind, String type, boolean mutable, Object value, int address, ScopeManager.Scope accessLevel) {
		this.name = name;
		this.kind = kind;
		this.type = type;
		this.mutable = mutable;
		this.value = value;
		this.address = address;
		this.accessLevel = accessLevel;
	}

	public String name () {
		return name;
	}

	public Kind kind () {
		return kind;
	}

	public String type () {
		return type;
	}

	public boolean isMutable () {
		return mutable;
	}

	public Object value () {
		return value;
	}

	public int address () {
		return address;
	}

	public ScopeManager.Scope accessLevel() {
		return accessLevel;
	}

	@Override
	public String toString () {
		StringBuilder sb = new StringBuilder();
		sb.append(kind).append(" ").append(name);
		if (type != null) {
			sb.append(" : ").append(type);
		}
		if (mutable) {
			sb.append(" (mutable)");
		}
		if (accessLevel != ScopeManager.Scope.DEFAULT) {
			sb.append(" [").append(accessLevel.toString().toLowerCase()).append("]");
		}
		return sb.toString();
	}
}
