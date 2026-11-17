package io.github._98woonho.dbscout.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github._98woonho.dbscout.connection.ConnectionRegistry;
import io.github._98woonho.dbscout.connection.Identifiers;
import io.github._98woonho.dbscout.schema.JoinPathFinder;
import io.github._98woonho.dbscout.schema.SchemaModel.Column;
import io.github._98woonho.dbscout.schema.SchemaModel.ForeignKey;
import io.github._98woonho.dbscout.schema.SchemaModel.Index;
import io.github._98woonho.dbscout.schema.SchemaModel.Table;
import io.github._98woonho.dbscout.schema.SchemaService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** DB 구조를 파악하는 MCP 도구: 테이블 목록, 테이블 상세, 이름 검색, 조인 경로. */
@Component
public class SchemaTools {

    static final int MAX_TABLES = 500;
    static final int MAX_JOIN_DEPTH = 6;
    static final int MAX_JOIN_PATHS = 3;
    private static final Set<String> GENERIC_COLUMNS = Set.of(
            "id", "last_update", "created_at", "updated_at", "create_date", "modified_at", "name", "description");

    private final SchemaService schema;

    public SchemaTools(SchemaService schema) {
        this.schema = schema;
    }

    // ───────────────────────── list_tables ─────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TableSummary(String schema, String name, String type, Long rows, String comment) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListTablesResult(String connection, int tableCount, String rowCounts, List<TableSummary> tables,
                                   String note) {
    }

    @McpTool(name = "list_tables",
            title = "테이블 목록",
            description = """
                    데이터베이스의 테이블과 뷰 목록을 행 수·설명과 함께 보여 준다. \
                    DB를 처음 볼 때 가장 먼저 쓴다. 행 수는 SQLite면 정확한 값, MySQL·PostgreSQL이면 통계 추정치다. \
                    테이블이 많으면 nameContains로 이름을 걸러 본다.""",
            annotations = @McpAnnotations(title = "테이블 목록", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult listTables(
            @McpToolParam(description = "이름에 이 글자가 들어간 테이블만 (대소문자 무시)", required = false) String nameContains,
            @McpToolParam(description = "접속 이름 (비우면 첫 번째 접속)", required = false) String connection) {
        return ToolResults.call("list_tables", () -> {
            ConnectionRegistry.Entry entry = schema.entry(connection);
            String filter = nameContains == null ? "" : nameContains.strip().toLowerCase(Locale.ROOT);
            List<Table> all = schema.tables(entry).stream()
                    .filter(t -> filter.isEmpty() || t.name().toLowerCase(Locale.ROOT).contains(filter))
                    .toList();
            List<TableSummary> tables = all.stream().limit(MAX_TABLES)
                    .map(t -> new TableSummary(t.schema(), t.name(), t.type(), schema.rowCount(entry, t).rows(), t.comment()))
                    .toList();
            String note = all.size() > MAX_TABLES
                    ? "테이블이 " + all.size() + "개라 앞의 " + MAX_TABLES + "개만 보여 줍니다. nameContains로 걸러 보세요."
                    : all.isEmpty() && !filter.isEmpty() ? "'" + nameContains + "'이(가) 들어간 테이블이 없습니다." : null;
            return new ListTablesResult(entry.name(), all.size(),
                    schema.rowCountsExact(entry) ? "정확한 값" : "통계 추정치", tables, note);
        });
    }

    // ───────────────────────── describe_table ─────────────────────────

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record DescribeResult(String table, String type, String comment, Long rows, Boolean rowsExact,
                                 List<Column> columns, List<String> primaryKey, List<Index> indexes,
                                 List<ForeignKey> references, List<ForeignKey> referencedBy, String note) {
    }

    @McpTool(name = "describe_table",
            title = "테이블 상세",
            description = """
                    테이블 하나의 컬럼(타입, 빈 값 허용, 기본값, 기본키, 설명), 인덱스, 외래키를 보여 준다. \
                    references는 이 테이블이 가리키는 테이블, referencedBy는 이 테이블을 가리키는 테이블이다. \
                    masked가 있는 컬럼은 조회 결과에서 개인정보로 가려진다.""",
            annotations = @McpAnnotations(title = "테이블 상세", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult describeTable(
            @McpToolParam(description = "테이블 이름 (스키마를 붙여도 됨: public.orders)") String table,
            @McpToolParam(description = "접속 이름 (비우면 첫 번째 접속)", required = false) String connection) {
        return ToolResults.call("describe_table", () -> {
            ConnectionRegistry.Entry entry = schema.entry(connection);
            Table t = schema.resolve(entry, table);
            List<Column> columns = schema.columns(entry, t);
            List<String> pk = columns.stream().filter(c -> Boolean.TRUE.equals(c.primaryKey())).map(Column::name).toList();
            SchemaService.RowCount rows = schema.rowCount(entry, t);
            boolean anyMasked = columns.stream().anyMatch(c -> c.masked() != null);
            return new DescribeResult(t.qualifiedName(), t.type(), t.comment(), rows.rows(),
                    rows.rows() == null ? null : rows.exact(), columns, pk,
                    t.isView() ? List.of() : schema.indexes(entry, t),
                    schema.outgoing(entry, t), schema.incoming(entry, t),
                    anyMasked ? "masked 표시가 있는 컬럼은 조회 결과에서 가려집니다. 원래 값은 볼 수 없습니다." : null);
        });
    }

    // ───────────────────────── search_schema ─────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TableMatch(String table, String type, String comment) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ColumnMatch(String table, String column, String type, String comment, String masked) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchResult(String keyword, List<TableMatch> tables, List<ColumnMatch> columns, String note) {
    }

    @McpTool(name = "search_schema",
            title = "구조 검색",
            description = """
                    키워드가 이름이나 설명에 들어간 테이블과 컬럼을 찾는다. \
                    "결제는 어느 테이블에 있지?"처럼 찾는 데이터의 위치를 모를 때 쓴다. \
                    공백으로 여러 키워드를 주면 하나라도 맞는 것을 찾는다(예: "payment amount 결제").""",
            annotations = @McpAnnotations(title = "구조 검색", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult searchSchema(
            @McpToolParam(description = "찾을 키워드 (공백으로 여러 개)") String keyword,
            @McpToolParam(description = "접속 이름 (비우면 첫 번째 접속)", required = false) String connection) {
        return ToolResults.call("search_schema", () -> {
            if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("키워드가 비어 있습니다.");
            List<String> terms = Arrays.stream(keyword.toLowerCase(Locale.ROOT).split("[\\s,]+"))
                    .filter(s -> !s.isBlank()).toList();
            ConnectionRegistry.Entry entry = schema.entry(connection);
            List<TableMatch> tables = new ArrayList<>();
            List<ColumnMatch> columns = new ArrayList<>();
            for (Map.Entry<Table, List<Column>> e : schema.allColumns(entry).entrySet()) {
                Table t = e.getKey();
                if (matches(terms, t.name(), t.comment())) tables.add(new TableMatch(t.qualifiedName(), t.type(), t.comment()));
                for (Column c : e.getValue()) {
                    if (matches(terms, c.name(), c.comment())) {
                        columns.add(new ColumnMatch(t.qualifiedName(), c.name(), c.type(), c.comment(), c.masked()));
                    }
                }
            }
            String note = null;
            if (tables.size() > 30 || columns.size() > 80) note = "결과가 많아 일부만 보여 줍니다. 키워드를 더 구체적으로 주세요.";
            if (tables.isEmpty() && columns.isEmpty()) {
                note = "맞는 테이블·컬럼이 없습니다. 영어 이름(예: payment, customer)이나 줄임말로도 찾아보세요.";
            }
            return new SearchResult(keyword, tables.stream().limit(30).toList(), columns.stream().limit(80).toList(), note);
        });
    }

    private static boolean matches(List<String> terms, String name, String comment) {
        String n = name.toLowerCase(Locale.ROOT);
        String c = comment == null ? "" : comment.toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(term -> n.contains(term) || c.contains(term));
    }

    // ───────────────────────── find_join_path ─────────────────────────

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record JoinPathResult(String from, String to, List<JoinPathFinder.JoinPath> paths,
                                 List<String> sameNameColumns, String note) {
    }

    @McpTool(name = "find_join_path",
            title = "조인 경로 찾기",
            description = """
                    두 테이블을 외래키로 잇는 가장 짧은 조인 경로를 찾아 바로 쓸 수 있는 FROM ... JOIN 절로 돌려준다. \
                    여러 테이블을 거쳐야 하는 질문(예: 영화별 매출 = film → inventory → rental → payment)에서 \
                    조인 조건을 추측하지 말고 이 도구로 확인한다. 같은 길이의 경로가 여러 개면 모두 보여 준다.""",
            annotations = @McpAnnotations(title = "조인 경로 찾기", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult findJoinPath(
            @McpToolParam(description = "출발 테이블") String fromTable,
            @McpToolParam(description = "도착 테이블") String toTable,
            @McpToolParam(description = "접속 이름 (비우면 첫 번째 접속)", required = false) String connection) {
        return ToolResults.call("find_join_path", () -> {
            ConnectionRegistry.Entry entry = schema.entry(connection);
            Table from = schema.resolve(entry, fromTable);
            Table to = schema.resolve(entry, toTable);
            if (from.equals(to)) throw new IllegalArgumentException("출발과 도착이 같은 테이블입니다.");
            List<JoinPathFinder.JoinPath> paths = JoinPathFinder.find(schema.foreignKeys(entry),
                    from.qualifiedName(), to.qualifiedName(), MAX_JOIN_DEPTH, MAX_JOIN_PATHS, entry.kind());
            if (!paths.isEmpty()) {
                String note = paths.size() > 1
                        ? "같은 길이의 경로가 " + paths.size() + "개입니다. 질문의 뜻에 맞는 경로를 고르세요."
                        : null;
                return new JoinPathResult(from.qualifiedName(), to.qualifiedName(), paths, List.of(), note);
            }
            // 외래키가 없을 때: 이름이 같은 컬럼을 '추정' 후보로 알려 준다
            Set<String> fromCols = new java.util.HashSet<>(
                    schema.columns(entry, from).stream().map(c -> Identifiers.normalize(c.name())).toList());
            List<String> same = schema.columns(entry, to).stream()
                    .map(Column::name)
                    .filter(c -> fromCols.contains(Identifiers.normalize(c)))
                    .filter(c -> !GENERIC_COLUMNS.contains(Identifiers.normalize(c)))
                    .toList();
            String note = "외래키로 " + MAX_JOIN_DEPTH + "단계 안에 이어지지 않습니다."
                    + (same.isEmpty() ? "" : " 이름이 같은 컬럼이 있지만 외래키가 아니므로, 값이 실제로 맞는지 확인한 뒤 쓰세요.");
            return new JoinPathResult(from.qualifiedName(), to.qualifiedName(), List.of(), same, note);
        });
    }
}
