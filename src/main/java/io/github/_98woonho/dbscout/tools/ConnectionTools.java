package io.github._98woonho.dbscout.tools;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.stereotype.Component;

import io.github._98woonho.dbscout.config.DbScoutProperties;
import io.github._98woonho.dbscout.config.DbScoutProperties.Limits;
import io.github._98woonho.dbscout.connection.ConnectionRegistry;

/** 접속 관련 MCP 도구. */
@Component
public class ConnectionTools {

    private final ConnectionRegistry registry;
    private final Limits limits;

    public ConnectionTools(ConnectionRegistry registry, DbScoutProperties properties) {
        this.registry = registry;
        this.limits = properties.limits();
    }

    /** 도구 응답. 접속 주소·계정·비밀번호는 넣지 않는다. */
    public record ConnectionInfo(String name, String type, String description, boolean demo) {
    }

    public record ListConnectionsResult(List<ConnectionInfo> connections, Limits limits, String note) {
    }

    @McpTool(name = "list_connections",
            title = "DB 접속 목록",
            description = """
                    이 서버에 등록된 데이터베이스 접속 목록과 조회 제한값을 보여 준다. \
                    다른 도구의 connection 인자에 여기 나온 name을 넣는다(비우면 첫 번째 접속). \
                    모든 조회는 읽기 전용이며 행 수·실행 시간 제한과 개인정보 가리기가 적용된다.""",
            annotations = @McpAnnotations(title = "DB 접속 목록", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public ListConnectionsResult listConnections() {
        List<ConnectionInfo> connections = registry.list().stream()
                .map(e -> new ConnectionInfo(e.name(), e.kind().displayName(), e.description(), e.demo()))
                .toList();
        String note = connections.stream().anyMatch(ConnectionInfo::demo)
                ? "설정된 DB가 없어 내장 데모 DB(Sakila)로 실행 중입니다."
                : null;
        return new ListConnectionsResult(connections, limits, note);
    }
}
