package concurrency.core;

import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;

/**
 * 极简 JSON 序列化器（只写不读，够用就行）。
 *
 * <p>为什么不用 Jackson/Gson？这个项目刻意保持零第三方依赖：
 * 克隆下来 {@code javac} 就能跑，不依赖 Maven 仓库、不依赖网络。
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(512);
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case CharSequence s -> quote(sb, s);
            case Boolean b -> sb.append(b);
            case Double d -> sb.append(d.isNaN() || d.isInfinite() ? "null" : d.toString());
            case Float f -> sb.append(f.isNaN() || f.isInfinite() ? "null" : f.toString());
            case Number n -> sb.append(n);
            case Enum<?> e -> quote(sb, e.name());
            case Map<?, ?> map -> {
                sb.append('{');
                Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<?, ?> entry = it.next();
                    quote(sb, String.valueOf(entry.getKey()));
                    sb.append(':');
                    append(sb, entry.getValue());
                    if (it.hasNext()) {
                        sb.append(',');
                    }
                }
                sb.append('}');
            }
            case Iterable<?> iterable -> {
                sb.append('[');
                Iterator<?> it = iterable.iterator();
                while (it.hasNext()) {
                    append(sb, it.next());
                    if (it.hasNext()) {
                        sb.append(',');
                    }
                }
                sb.append(']');
            }
            default -> {
                if (value.getClass().isRecord()) {
                    appendRecord(sb, value);
                } else if (value.getClass().isArray()) {
                    sb.append('[');
                    int len = Array.getLength(value);
                    for (int i = 0; i < len; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        append(sb, Array.get(value, i));
                    }
                    sb.append(']');
                } else {
                    quote(sb, String.valueOf(value));
                }
            }
        }
    }

    /** record 直接反射成 JSON 对象，省掉手写 toMap。 */
    private static void appendRecord(StringBuilder sb, Object value) {
        sb.append('{');
        java.lang.reflect.RecordComponent[] components = value.getClass().getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            java.lang.reflect.RecordComponent component = components[i];
            if (i > 0) {
                sb.append(',');
            }
            quote(sb, component.getName());
            sb.append(':');
            try {
                append(sb, component.getAccessor().invoke(value));
            } catch (ReflectiveOperationException e) {
                sb.append("null");
            }
        }
        sb.append('}');
    }

    private static void quote(StringBuilder sb, CharSequence s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
