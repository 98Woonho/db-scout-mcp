package io.github._98woonho.dbscout.query;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github._98woonho.dbscout.config.DbScoutProperties;
import io.github._98woonho.dbscout.config.DbScoutProperties.Limits;
import io.github._98woonho.dbscout.connection.ConnectionRegistry;
import io.github._98woonho.dbscout.connection.ReadOnlyConnections;
import io.github._98woonho.dbscout.connection.SakilaDemo;
import io.github._98woonho.dbscout.guard.SqlGuard;

/** 내장 Sakila 데모 DB로 실행기를 만든다. */
final class SakilaFixture {

    static final Limits DEFAULT_LIMITS = new Limits(100, 1000, 10, 60, 200, 50_000);

    private SakilaFixture() {
    }

    static QueryExecutor executor(Path dir) {
        return executor(dir, DEFAULT_LIMITS);
    }

    static QueryExecutor executor(Path dir, Limits limits) {
        Path db = SakilaDemo.extract(dir);
        var props = new DbScoutProperties(List.of(), limits);
        var registry = new ConnectionRegistry(props, () -> "jdbc:sqlite:" + db);
        return new QueryExecutor(registry, new ReadOnlyConnections(), new SqlGuard(), props);
    }

    /** 결과에서 한 컬럼의 값들. */
    static List<Object> column(QueryResult result, String name) {
        int i = result.columns().indexOf(name);
        if (i < 0) throw new AssertionError("컬럼 없음: " + name + " / " + result.columns());
        List<Object> values = new ArrayList<>();
        for (List<Object> row : result.rows()) values.add(row.get(i));
        return values;
    }
}
