package io.github._98woonho.dbscout.query;

import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Locale;

import io.github._98woonho.dbscout.mask.MaskKind;
import io.github._98woonho.dbscout.mask.Masker;

/** DB 값을 JSON으로 보낼 수 있는 값으로 바꾸고, 가리고, 너무 길면 자른다. */
final class CellFormatter {

    /** 바뀐 값과, 글자 수 제한으로 잘렸는지. */
    record Cell(Object value, boolean truncated) {
    }

    private final int maxChars;

    CellFormatter(int maxChars) {
        this.maxChars = maxChars;
    }

    Cell format(Object raw, MaskKind mask, boolean derived) throws SQLException {
        if (raw == null) return new Cell(null, false);
        if (raw instanceof byte[] bytes) return new Cell(binary(bytes.length), false);
        if (raw instanceof Blob blob) return new Cell(binary(blob.length()), false);

        Object value = toPlain(raw);
        if (mask != null) value = Masker.mask(value, mask, derived);
        if (value instanceof String s && s.length() > maxChars) {
            return new Cell(s.substring(0, maxChars) + "…(+" + (s.length() - maxChars) + "자)", true);
        }
        return new Cell(value, false);
    }

    private Object toPlain(Object raw) throws SQLException {
        return switch (raw) {
            case String s -> s;
            case Boolean b -> b;
            case Double d when d.isNaN() || d.isInfinite() -> d.toString();
            case Float f when f.isNaN() || f.isInfinite() -> f.toString();
            case Number n -> n;
            case Timestamp t -> t.toLocalDateTime().toString();
            case Time t -> t.toLocalTime().toString();
            case java.sql.Date d -> d.toLocalDate().toString();
            case Clob c -> c.getSubString(1, (int) Math.min(c.length(), maxChars + 1L));
            case Array a -> String.valueOf(a.getArray() instanceof Object[] arr ? java.util.Arrays.toString(arr) : a);
            default -> raw.toString();
        };
    }

    static String binary(long bytes) {
        if (bytes < 1024) return "[binary " + bytes + "B]";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "[binary %.1fKB]", bytes / 1024.0);
        return String.format(Locale.ROOT, "[binary %.1fMB]", bytes / (1024.0 * 1024));
    }

    /** JSON으로 보냈을 때의 대략적인 글자 수(응답 크기 제한용). */
    static int jsonLength(Object value) {
        if (value == null) return 4;
        if (value instanceof String s) return s.length() + 2;
        return value.toString().length();
    }
}
