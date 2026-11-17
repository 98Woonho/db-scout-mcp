package io.github._98woonho.dbscout.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github._98woonho.dbscout.connection.ConnectionRegistry;
import io.github._98woonho.dbscout.connection.Identifiers;
import io.github._98woonho.dbscout.query.QueryExecutor;
import io.github._98woonho.dbscout.query.QueryResult;
import io.github._98woonho.dbscout.schema.SchemaModel.Column;
import io.github._98woonho.dbscout.schema.SchemaModel.Table;
import io.github._98woonho.dbscout.schema.SchemaService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * 데이터를 맛보는 MCP 도구: 샘플 행, 컬럼 통계.
 * 둘 다 서버가 만든 SELECT를 run_query와 같은 길(검사 → 읽기 전용 실행 → 가리기)로 실행한다.
 */
@Component
public class DataTools {

    static final int DEFAULT_SAMPLE = 5;
    static final int MAX_SAMPLE = 20;
    static final int TOP_VALUES = 10;

    private final SchemaService schema;
    private final QueryExecutor executor;

    public DataTools(SchemaService schema, QueryExecutor executor) {
        this.schema = schema;
        this.executor = executor;
    }

    @McpTool(name = "sample_rows",
            title = "샘플 행",
            description = """
                    테이블의 앞쪽 몇 행을 보여 준다(기본 5행, 최대 20행). \
                    값의 실제 모양(날짜 형식, 코드 값, 빈 값 표시 등)을 확인한 뒤 SQL을 쓸 때 사용한다. \
                    개인정보 컬럼은 가려져 나온다.""",
            annotations = @McpAnnotations(title = "샘플 행", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult sampleRows(
            @McpToolParam(description = "테이블 이름") String table,
            @McpToolParam(description = "행 수 (기본 5, 최대 20)", required = false) Integer limit,
            @McpToolParam(description = "접속 이름 (비우면 첫 번째 접속)", required = false) String connection) {
        return ToolResults.call("sample_rows", () -> {
            ConnectionRegistry.Entry entry = schema.entry(connection);
            Table t = schema.resolve(entry, table);
            int n = limit == null || limit <= 0 ? DEFAULT_SAMPLE : Math.min(limit, MAX_SAMPLE);
            String sql = "SELECT * FROM " + Identifiers.quote(entry.kind(), t.schema(), t.name());
            return executor.run(entry.name(), sql, n, null);
        });
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ValueCount(Object value, Object count) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ColumnProfile(String table, String column, String type, String masked,
                                Object totalRows, Object nullCount, Object distinctCount, Object min, Object max,
                                List<ValueCount> topValues, String note) {
    }

    @McpTool(name = "profile_column",
            title = "컬럼 통계",
            description = """
                    컬럼 하나의 값 분포를 보여 준다: 전체 행 수, 빈 값(NULL) 수, 서로 다른 값 수, 최소·최대, 자주 나오는 값 10개. \
                    상태 코드처럼 어떤 값들이 있는지, 빈 값이 많은지 확인할 때 쓴다. 개인정보 컬럼은 값이 가려져 나온다.""",
            annotations = @McpAnnotations(title = "컬럼 통계", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult profileColumn(
            @McpToolParam(description = "테이블 이름") String table,
            @McpToolParam(description = "컬럼 이름") String column,
            @McpToolParam(description = "접속 이름 (비우면 첫 번째 접속)", required = false) String connection) {
        return ToolResults.call("profile_column", () -> {
            ConnectionRegistry.Entry entry = schema.entry(connection);
            Table t = schema.resolve(entry, table);
            List<Column> columns = schema.columns(entry, t);
            String wanted = Identifiers.normalize(column == null ? "" : column);
            Column c = columns.stream().filter(x -> Identifiers.normalize(x.name()).equals(wanted)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("'" + t.qualifiedName() + "'에 '" + column
                            + "' 컬럼이 없습니다. 컬럼: " + String.join(", ", columns.stream().map(Column::name).toList())));

            String from = Identifiers.quote(entry.kind(), t.schema(), t.name());
            String col = Identifiers.quote(entry.kind(), c.name());
            boolean binary = c.type() != null && c.type().toUpperCase(Locale.ROOT).matches(".*(BLOB|BINARY|BYTEA|IMAGE).*");

            String stats = binary
                    ? "SELECT COUNT(*) AS total_rows, COUNT(" + col + ") AS non_null FROM " + from
                    : "SELECT COUNT(*) AS total_rows, COUNT(" + col + ") AS non_null, COUNT(DISTINCT " + col
                            + ") AS distinct_values, MIN(" + col + ") AS min_value, MAX(" + col + ") AS max_value FROM " + from;
            List<Object> s = executor.run(entry.name(), stats, 1, null).rows().getFirst();
            Object total = s.get(0);
            Object nulls = (total instanceof Number tn && s.get(1) instanceof Number nn) ? tn.longValue() - nn.longValue() : null;

            List<ValueCount> top = new ArrayList<>();
            String note = null;
            if (binary) {
                note = "바이너리 컬럼이라 값 분포는 보여 주지 않습니다.";
            } else {
                String topSql = "SELECT " + col + " AS value, COUNT(*) AS frequency FROM " + from
                        + " GROUP BY " + col + " ORDER BY COUNT(*) DESC LIMIT " + TOP_VALUES;
                QueryResult r = executor.run(entry.name(), topSql, TOP_VALUES, null);
                for (List<Object> row : r.rows()) top.add(new ValueCount(row.get(0), row.get(1)));
                if (c.masked() != null) note = "개인정보 컬럼이라 값은 가려서 보여 줍니다.";
            }
            return new ColumnProfile(t.qualifiedName(), c.name(), c.type(), c.masked(), total, nulls,
                    binary ? null : s.get(2), binary ? null : s.get(3), binary ? null : s.get(4), top, note);
        });
    }
}
