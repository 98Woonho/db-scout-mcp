package io.github._98woonho.dbscout.guard;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github._98woonho.dbscout.connection.ConnectionRegistry;
import io.github._98woonho.dbscout.connection.DbKind;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;

/**
 * 안전장치 1겹째: 실행 전에 SQL을 분석해 읽기 전용 SELECT 한 문장만 통과시킨다.
 *
 * <ol>
 *   <li>{@link SqlLexer}: 주석을 지우고, DB마다 해석이 갈리는 문법을 막는다.</li>
 *   <li>JSqlParser로 분석: 한 문장인지, SELECT인지 확인한다. 분석하지 못하면 실행하지 않는다.</li>
 *   <li>{@link SelectInspector}: 문장 전체에서 금지 함수·테이블, INTO, 행 잠금, 쿼리 안의 쓰기를 찾는다.</li>
 * </ol>
 *
 * <p>통과한 SQL은 {@link CheckedQuery#sql()}(주석을 지운 SQL)로 실행해야 한다.
 * 그래야 검사한 SQL과 DB가 실행하는 SQL이 같다.
 */
@Component
public class SqlGuard {

    public static final int MAX_SQL_LENGTH = 20_000;
    private static final long PARSE_TIMEOUT_MS = 5_000;

    /** 검사를 통과한 SQL. */
    public record CheckedQuery(String sql, Select select, Set<String> tables, Set<String> functions) {
    }

    /** 접속 설정까지 반영해 검사한다: 설정에서 숨긴 테이블을 쓰면 막는다. */
    public CheckedQuery check(String sql, ConnectionRegistry.Entry entry) {
        CheckedQuery checked = check(sql, entry.kind());
        for (String table : checked.tables()) {
            String[] parts = table.split("\\.");
            String name = parts[parts.length - 1];
            String schema = parts.length >= 2 ? parts[parts.length - 2] : null;
            if (entry.isHidden(schema, name)) {
                throw new QueryRejectedException(RejectReason.FORBIDDEN_TABLE,
                        table + " 은(는) 이 접속의 설정에서 숨긴 테이블이라 조회할 수 없습니다.");
            }
        }
        return checked;
    }

    public CheckedQuery check(String sql, DbKind kind) {
        if (sql == null || sql.isBlank()) {
            throw new QueryRejectedException(RejectReason.EMPTY, "실행할 SQL이 비어 있습니다.");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new QueryRejectedException(RejectReason.TOO_LONG,
                    "SQL이 " + MAX_SQL_LENGTH + "자를 넘습니다. 필요한 부분만 조회하도록 줄이세요.");
        }

        SqlLexer.Result lexed = SqlLexer.clean(sql, kind);
        if (lexed.sql().isEmpty()) {
            throw new QueryRejectedException(RejectReason.EMPTY, "주석을 빼면 실행할 SQL이 없습니다.");
        }
        if (lexed.hasStatementSeparator()) {
            throw multipleStatements();
        }

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(lexed.sql(), parser -> {
                parser.withTimeOut(PARSE_TIMEOUT_MS);
                if (kind == DbKind.SQLITE) parser.withSquareBracketQuotation(true);
            });
        } catch (JSQLParserException e) {
            // 분석기가 모르는 문장(PRAGMA, VACUUM 등)이라도 조회 문장이 아니면 이유를 분명히 알려 준다
            if (!startsLikeQuery(lexed.sql())) throw notSelect(lexed.sql());
            throw new QueryRejectedException(RejectReason.PARSE_ERROR,
                    "SQL을 분석하지 못해 실행하지 않았습니다. 문법을 확인하세요. (" + firstLine(e) + ")");
        }
        if (statements == null || statements.isEmpty()) {
            throw new QueryRejectedException(RejectReason.EMPTY, "실행할 SQL이 없습니다.");
        }
        if (statements.size() > 1) {
            throw multipleStatements();
        }

        Statement statement = statements.getFirst();
        if (!(statement instanceof Select select)) {
            throw notSelect(lexed.sql());
        }

        SelectInspector inspector = new SelectInspector();
        Set<String> tables = inspector.inspect(select);
        return new CheckedQuery(lexed.sql(), select, Set.copyOf(tables), Set.copyOf(inspector.functions()));
    }

    private static QueryRejectedException multipleStatements() {
        return new QueryRejectedException(RejectReason.MULTIPLE_STATEMENTS,
                "한 번에 SELECT 한 문장만 실행할 수 있습니다. 세미콜론(;)으로 문장을 이어 붙이지 마세요.");
    }

    private static QueryRejectedException notSelect(String sql) {
        return new QueryRejectedException(RejectReason.NOT_SELECT,
                "읽기 전용 서버라 SELECT 문만 실행할 수 있습니다. 받은 문장: " + firstWord(sql)
                        + ". 구조는 describe_table 같은 도구로, 실행 계획은 explain_query로 확인하세요.");
    }

    private static boolean startsLikeQuery(String sql) {
        if (sql.startsWith("(")) return true;
        return Set.of("SELECT", "WITH", "VALUES", "TABLE").contains(firstWord(sql));
    }

    private static String firstWord(String sql) {
        String word = sql.strip().split("[\\s(]+", 2)[0];
        return word.toUpperCase(Locale.ROOT);
    }

    private static String firstLine(JSQLParserException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().strip();
        int nl = message.indexOf('\n');
        String line = nl < 0 ? message : message.substring(0, nl);
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
