package io.github._98woonho.dbscout.it;

import java.util.List;

import io.github._98woonho.dbscout.config.DbScoutProperties;
import io.github._98woonho.dbscout.config.DbScoutProperties.ConnectionConfig;
import io.github._98woonho.dbscout.config.DbScoutProperties.Limits;
import io.github._98woonho.dbscout.connection.ConnectionRegistry;
import io.github._98woonho.dbscout.connection.ReadOnlyConnections;
import io.github._98woonho.dbscout.connection.SakilaDemo;
import io.github._98woonho.dbscout.guard.SqlGuard;
import io.github._98woonho.dbscout.query.ExplainService;
import io.github._98woonho.dbscout.query.QueryExecutor;
import io.github._98woonho.dbscout.schema.SchemaService;
import io.github._98woonho.dbscout.tools.DataTools;
import io.github._98woonho.dbscout.tools.QueryTools;
import io.github._98woonho.dbscout.tools.SchemaTools;

/**
 * 환경변수로 받은 실제 DB(MySQL·PostgreSQL)에 서버 부품을 연결한다.
 *
 * <p>{@code DB_SCOUT_IT_<DB>_URL / _USER / _PASSWORD}. CI에서는 일부러 <b>쓰기 권한이 있는 관리자 계정</b>을 쓴다.
 * 계정이 쓸 수 있어도 서버의 안전장치가 막는지 확인하기 위해서다.
 */
record IntegrationStack(ConnectionRegistry registry, ConnectionRegistry.Entry entry, ReadOnlyConnections connections,
                        QueryExecutor executor, SchemaTools schemaTools, DataTools dataTools, QueryTools queryTools) {

    static final Limits LIMITS = new Limits(100, 1000, 10, 60, 200, 50_000);

    static String env(String prefix, String suffix) {
        return System.getenv("DB_SCOUT_IT_" + prefix + "_" + suffix);
    }

    static IntegrationStack fromEnv(String prefix) {
        var config = new ConnectionConfig("it", env(prefix, "URL"), env(prefix, "USER"), env(prefix, "PASSWORD"),
                "통합 테스트", SakilaDemo.MASK_COLUMNS, List.of(), List.of());
        var props = new DbScoutProperties(List.of(config), LIMITS);
        var registry = new ConnectionRegistry(props, () -> "unused");
        var connections = new ReadOnlyConnections();
        var guard = new SqlGuard();
        var executor = new QueryExecutor(registry, connections, guard, props);
        var schema = new SchemaService(registry, connections);
        return new IntegrationStack(registry, registry.get("it"), connections, executor,
                new SchemaTools(schema), new DataTools(schema, executor),
                new QueryTools(executor, new ExplainService(registry, connections, guard, props)));
    }
}
