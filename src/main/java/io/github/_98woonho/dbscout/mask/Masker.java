package io.github._98woonho.dbscout.mask;

/**
 * 값을 가린다. 원래 값의 형태를 조금 남겨 "어떤 종류의 값인지"는 알 수 있게 하되, 사람을 특정할 수는 없게 한다.
 *
 * <ul>
 *   <li>이메일: {@code mary.smith@example.com} → {@code m***@example.com}</li>
 *   <li>전화번호: {@code 010-1234-5678} → {@code ***-****-5678} (숫자는 끝 4자리만)</li>
 *   <li>생년월일: {@code 1990-05-03} → {@code 1990-**-**}</li>
 *   <li>일부 가림: {@code 47 MySakila Drive} → {@code 47 ***}</li>
 *   <li>전부 가림: 항상 {@code ****} (길이도 알 수 없게)</li>
 * </ul>
 */
public final class Masker {

    public static final String HIDDEN = "****";

    private Masker() {
    }

    /**
     * @param derived 원본 컬럼 그대로가 아니라 함수·연산을 거친 값이면 true. 형식이 달라졌을 수 있으므로
     *                이메일·전화번호 형식으로 가리지 않고 '일부 가림'으로 처리한다(전부 가림은 그대로).
     */
    public static Object mask(Object value, MaskKind kind, boolean derived) {
        if (value == null || kind == null) return value;
        String s = value.toString();
        if (kind == MaskKind.SECRET) return HIDDEN;
        if (derived) return partial(s);
        return switch (kind) {
            case EMAIL -> email(s);
            case PHONE -> phone(s);
            case BIRTH -> birth(s);
            default -> partial(s);
        };
    }

    static String email(String s) {
        int at = s.indexOf('@');
        if (at <= 0 || at != s.lastIndexOf('@')) return partial(s);
        return s.charAt(0) + "***" + s.substring(at);
    }

    static String phone(String s) {
        long digits = s.chars().filter(Character::isDigit).count();
        if (digits <= 4) return HIDDEN;
        long keepFrom = digits - 4;
        StringBuilder out = new StringBuilder(s.length());
        int seen = 0;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                out.append(seen++ < keepFrom ? '*' : c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static String birth(String s) {
        // 연도(앞 4자리 숫자)만 남긴다
        if (s.length() >= 4 && s.substring(0, 4).chars().allMatch(Character::isDigit)) {
            return s.substring(0, 4) + s.substring(4).replaceAll("[0-9]", "*");
        }
        return HIDDEN;
    }

    static String partial(String s) {
        String trimmed = s.strip();
        if (trimmed.length() <= 1) return HIDDEN;
        int keep = Math.min(3, Math.max(1, trimmed.length() / 4));
        return trimmed.substring(0, keep) + "***";
    }
}
