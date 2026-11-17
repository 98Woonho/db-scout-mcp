package io.github._98woonho.dbscout.mask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 어떤 컬럼이 개인정보인지 정하는 규칙.
 *
 * <ul>
 *   <li>기본 규칙: 컬럼 이름이 email, phone, password 처럼 끝나면 개인정보로 본다.
 *       {@code address_id}처럼 키 컬럼은 이름이 id로 끝나므로 해당하지 않는다.</li>
 *   <li>추가 규칙: 설정의 {@code mask-columns}에 {@code 테이블.컬럼} 또는 {@code 컬럼}으로 적는다.
 *       뒤에 {@code =secret|email|phone|birth|partial}을 붙여 가리는 방식을 고를 수 있다(기본 partial).</li>
 *   <li>예외: {@code unmask-columns}에 적은 컬럼은 기본 규칙에 걸려도 가리지 않는다.</li>
 * </ul>
 *
 * <p>컬럼 이름은 대소문자·따옴표·camelCase·공백을 무시하고 비교한다({@code phoneNumber} = {@code phone_number}).
 */
public final class MaskPolicy {

    private record NameRule(Pattern pattern, MaskKind kind) {
    }

    /** 테이블이 null이면 모든 테이블의 그 컬럼. */
    private record ColumnRule(String table, String column, MaskKind kind) {
        boolean matches(String t, String c) {
            return column.equals(c) && (table == null || table.equals(t));
        }
    }

    private static final List<NameRule> DEFAULT_RULES = List.of(
            new NameRule(Pattern.compile(
                    "(^|_)(password|passwd|pwd|passcode|secret|token|api_key|apikey|salt|otp|pin|private_key"
                            + "|credential|credentials|authentication_string|ssn|rrn|jumin|resident_no|resident_number"
                            + "|card_no|card_number|cvv|cvc|account_no|account_number)(_hash|_digest)?$"),
                    MaskKind.SECRET),
            new NameRule(Pattern.compile("(^|_)e_?mail(_address|_addr)?$"), MaskKind.EMAIL),
            new NameRule(Pattern.compile(
                    "(^|_)(phone|mobile|tel|telephone|cellphone|cell_phone|fax|hp)(_number|_no|_num)?$"),
                    MaskKind.PHONE),
            new NameRule(Pattern.compile("(^|_)(birth|birthday|birthdate|birth_date|date_of_birth|dob)$"),
                    MaskKind.BIRTH),
            new NameRule(Pattern.compile(
                    "(^|_)(address|address1|address2|addr|street|postal_code|postcode|zip|zipcode|zip_code)$"),
                    MaskKind.PARTIAL));

    private final List<ColumnRule> extra;
    private final List<ColumnRule> exempt;

    private MaskPolicy(List<ColumnRule> extra, List<ColumnRule> exempt) {
        this.extra = extra;
        this.exempt = exempt;
    }

    public static MaskPolicy defaults() {
        return of(List.of(), List.of());
    }

    public static MaskPolicy of(List<String> maskColumns, List<String> unmaskColumns) {
        return new MaskPolicy(parse(maskColumns, true), parse(unmaskColumns, false));
    }

    /**
     * 이 컬럼을 가려야 하면 가리는 방식, 아니면 null.
     *
     * @param table 컬럼이 속한 테이블(모르면 null). 테이블을 지정한 추가 규칙은 테이블을 알 때만 적용된다.
     */
    public MaskKind kindFor(String table, String column) {
        if (column == null) return null;
        String c = normalize(column);
        String t = table == null ? null : normalize(table);
        for (ColumnRule r : exempt) {
            if (r.matches(t, c)) return null;
        }
        MaskKind kind = null;
        for (ColumnRule r : extra) {
            if (r.matches(t, c)) kind = MaskKind.strongest(kind, r.kind());
        }
        for (NameRule r : DEFAULT_RULES) {
            if (r.pattern().matcher(c).find()) kind = MaskKind.strongest(kind, r.kind());
        }
        return kind;
    }

    /** 따옴표·스키마를 벗기고 camelCase와 공백을 밑줄로 바꿔 소문자로. */
    static String normalize(String identifier) {
        String s = identifier.strip();
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && !s.endsWith("\"") && !s.endsWith("`")) s = s.substring(dot + 1);
        s = s.replaceAll("^[\"`\\[]|[\"`\\]]$", "");
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        s = s.replaceAll("[\\s-]+", "_");
        return s.toLowerCase(Locale.ROOT);
    }

    private static List<ColumnRule> parse(List<String> specs, boolean allowKind) {
        List<ColumnRule> rules = new ArrayList<>();
        if (specs == null) return rules;
        for (String raw : specs) {
            if (raw == null || raw.isBlank()) continue;
            String spec = raw.strip();
            MaskKind kind = MaskKind.PARTIAL;
            int eq = spec.indexOf('=');
            if (eq >= 0) {
                if (!allowKind) throw new IllegalArgumentException("unmask-columns에는 '='를 쓸 수 없습니다: " + raw);
                kind = parseKind(spec.substring(eq + 1).strip(), raw);
                spec = spec.substring(0, eq).strip();
            }
            int dot = spec.lastIndexOf('.');
            String table = dot < 0 ? null : normalizeTable(spec.substring(0, dot));
            String column = normalize(dot < 0 ? spec : spec.substring(dot + 1));
            if (column.isEmpty()) throw new IllegalArgumentException("컬럼 이름이 비어 있습니다: " + raw);
            rules.add(new ColumnRule(table, column, kind));
        }
        return List.copyOf(rules);
    }

    private static String normalizeTable(String table) {
        return normalize(table); // schema.table 이면 table 만 남는다
    }

    private static MaskKind parseKind(String value, String raw) {
        try {
            return MaskKind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "가리는 방식은 secret, email, phone, birth, partial 중 하나여야 합니다: " + raw);
        }
    }
}
