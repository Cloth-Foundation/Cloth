package semantic

// Type metadata helpers for double-colon access, e.g., i32::MIN, i32::BITS.
// Returned values use runtime numeric representations:
// - integers as int64
// - floats as float64
// Note: u64::MAX exceeds int64 and is returned as float64.

// evalTypeMetadata resolves metadata constant names for a builtin type name.
// Supported names: MIN, MAX, BITS, BYTES
func evalTypeMetadata(typeName, name string) (any, bool) {
	bits := func(t string) (int64, bool) {
		switch t {
		case "i8", "u8", "byte":
			return 8, true
		case "i16", "u16":
			return 16, true
		case "i32", "u32", "f32":
			return 32, true
		case "i64", "u64", "f64":
			return 64, true
		}
		return 0, false
	}
	bytes := func(t string) (int64, bool) {
		if b, ok := bits(t); ok {
			return b / 8, true
		}
		return 0, false
	}
	min := func(t string) (any, bool) {
		switch t {
		case "i8":
			return int64(-128), true
		case "i16":
			return int64(-32768), true
		case "i32":
			return int64(-2147483648), true
		case "i64":
			return int64(-9223372036854775808), true
		case "u8", "byte", "u16", "u32", "u64":
			return int64(0), true
		case "f32":
			return -3.4028234663852886e+38, true
		case "f64":
			return -1.7976931348623157e+308, true
		}
		return nil, false
	}
	max := func(t string) (any, bool) {
		switch t {
		case "i8":
			return int64(127), true
		case "i16":
			return int64(32767), true
		case "i32":
			return int64(2147483647), true
		case "i64":
			return int64(9223372036854775807), true
		case "u8", "byte":
			return int64(255), true
		case "u16":
			return int64(65535), true
		case "u32":
			return int64(4294967295), true
		case "u64":
			return 1.8446744073709552e+19, true
		case "f32":
			return 3.4028234663852886e+38, true
		case "f64":
			return 1.7976931348623157e+308, true
		}
		return nil, false
	}
	switch name {
	case "BITS":
		if b, ok := bits(typeName); ok {
			return b, true
		}
	case "BYTES":
		if by, ok := bytes(typeName); ok {
			return by, true
		}
	case "MIN":
		if v, ok := min(typeName); ok {
			return v, true
		}
	case "MAX":
		if v, ok := max(typeName); ok {
			return v, true
		}
	}
	return nil, false
}
