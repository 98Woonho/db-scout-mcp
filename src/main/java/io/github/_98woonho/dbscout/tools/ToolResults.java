package io.github._98woonho.dbscout.tools;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github._98woonho.dbscout.guard.QueryRejectedException;
import io.github._98woonho.dbscout.query.QueryFailedException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도구 응답 만들기. 결과는 JSON 글로, 실패는 isError 응답으로 돌려준다.
 *
 * <p>예상한 실패(검사에서 막힘, 실행 실패, 잘못된 인자)는 이유를 그대로 알려 Claude가 고칠 수 있게 한다.
 * 예상하지 못한 예외는 메시지에 무엇이 들었을지 모르므로(데이터 값 등) 종류만 알린다.
 */
final class ToolResults {

    private static final Logger log = LoggerFactory.getLogger(ToolResults.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolResults() {
    }

    static CallToolResult call(String tool, Supplier<Object> body) {
        try {
            return ok(body.get());
        } catch (QueryRejectedException | QueryFailedException | IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            log.error("{} 도구 실행 중 예상하지 못한 오류", tool, e);
            return error("내부 오류로 실패했습니다(" + e.getClass().getSimpleName() + "). 다른 방법으로 다시 시도하세요.");
        }
    }

    static CallToolResult ok(Object value) {
        return CallToolResult.builder().addTextContent(JSON.writeValueAsString(value)).isError(false).build();
    }

    static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
