package io.github._98woonho.dbscout.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import io.github._98woonho.dbscout.query.ExplainService;
import io.github._98woonho.dbscout.query.QueryExecutor;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** SQL을 실행하는 MCP 도구: 실행 계획 보기, 조회. */
@Component
public class QueryTools {

    private final QueryExecutor executor;
    private final ExplainService explainer;

    public QueryTools(QueryExecutor executor, ExplainService explainer) {
        this.executor = executor;
        this.explainer = explainer;
    }

    @McpTool(name = "explain_query",
            title = "실행 계획 보기",
            description = """
                    SELECT 문을 실제로 실행하지 않고 DB의 실행 계획만 보여 준다. \
                    큰 테이블을 조회하기 전에 인덱스를 쓰는지, 테이블 전체를 읽는지(fullScanTables) 확인할 때 쓴다. \
                    run_query와 같은 안전 검사를 거친다.""",
            annotations = @McpAnnotations(title = "실행 계획 보기", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult explainQuery(
            @McpToolParam(description = "실행 계획을 볼 SELECT 문 (EXPLAIN은 붙이지 않는다)") String sql,
            @McpToolParam(description = "접속 이름 (비우면 첫 번째 접속)", required = false) String connection) {
        return ToolResults.call("explain_query", () -> explainer.explain(connection, sql));
    }

    @McpTool(name = "run_query",
            title = "SQL 조회",
            description = """
                    읽기 전용 SELECT 문 하나를 실행해 결과를 돌려준다. \
                    INSERT·UPDATE·DELETE·DDL·여러 문장은 실행되지 않는다. \
                    결과는 행 수(기본 100행)와 실행 시간(기본 10초)이 제한되고, 개인정보 컬럼은 가려진다. \
                    truncated가 true면 결과가 잘린 것이므로, 많은 행을 가져오기보다 SQL 안에서 집계(GROUP BY, COUNT, SUM)한다. \
                    rows는 columns 순서의 값 배열이다.""",
            annotations = @McpAnnotations(title = "SQL 조회", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult runQuery(
            @McpToolParam(description = "실행할 SELECT 문 하나") String sql,
            @McpToolParam(description = "최대 행 수 (기본 100, 최대 1000)", required = false) Integer maxRows,
            @McpToolParam(description = "실행 시간 제한(초) (기본 10, 최대 60)", required = false) Integer timeoutSeconds,
            @McpToolParam(description = "접속 이름 (비우면 첫 번째 접속)", required = false) String connection) {
        return ToolResults.call("run_query", () -> executor.run(connection, sql, maxRows, timeoutSeconds));
    }
}
