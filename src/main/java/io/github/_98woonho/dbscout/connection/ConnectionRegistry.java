package io.github._98woonho.dbscout.connection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github._98woonho.dbscout.config.DbScoutProperties;
import io.github._98woonho.dbscout.config.DbScoutProperties.ConnectionConfig;
import io.github._98woonho.dbscout.mask.MaskPolicy;

/**
 * 이름 붙은 DB 접속 목록.
 *
 * <p>설정된 접속이 하나도 없으면 내장 Sakila 데모 DB 하나를 등록한다.
 * 그래서 설정 없이 실행해도 서버가 뜨고 도구를 써 볼 수 있다.
 */
@Component
public class ConnectionRegistry {

    /**
     * 등록된 접속 하나. 비밀번호는 여기서만 들고 있고 밖으로 내보내지 않는다.
     *
     * @param hiddenTables 숨긴 테이블. 소문자 {@code 테이블} 또는 {@code 스키마.테이블}
     */
    public record Entry(String name, DbKind kind, String description, boolean demo,
                        String url, String username, String password, MaskPolicy maskPolicy,
                        Set<String> hiddenTables) {

        /**
         * 숨긴 테이블인지. 스키마를 모르면(SQLite이거나 SQL에 스키마를 안 붙인 경우) {@code 아무스키마.테이블}
         * 설정도 맞는 것으로 본다(더 많이 막는 쪽).
         */
        public boolean isHidden(String schema, String table) {
            if (hiddenTables.isEmpty() || table == null) return false;
            String t = Identifiers.normalize(table);
            if (hiddenTables.contains(t)) return true;
            if (schema == null) return hiddenTables.stream().anyMatch(h -> h.endsWith("." + t));
            return hiddenTables.contains(Identifiers.normalize(schema) + "." + t);
        }

        @Override
        public String toString() {
            return "Entry[name=" + name + ", kind=" + kind + "]";
        }
    }

    private final Map<String, Entry> entries;

    @Autowired
    public ConnectionRegistry(DbScoutProperties properties) {
        this(properties, SakilaDemo::jdbcUrl);
    }

    /** 데모 DB 경로를 바꿀 수 있는 생성자(테스트용). */
    public ConnectionRegistry(DbScoutProperties properties, Supplier<String> demoUrl) {
        List<ConnectionConfig> configured = properties.connections().stream()
                .filter(ConnectionConfig::isConfigured)
                .toList();
        Map<String, Entry> map = new LinkedHashMap<>();
        if (configured.isEmpty()) {
            map.put(SakilaDemo.CONNECTION_NAME, new Entry(SakilaDemo.CONNECTION_NAME, DbKind.SQLITE,
                    SakilaDemo.DESCRIPTION, true, demoUrl.get(), null, null,
                    MaskPolicy.of(SakilaDemo.MASK_COLUMNS, List.of()), Set.of()));
        }
        for (int i = 0; i < configured.size(); i++) {
            ConnectionConfig c = configured.get(i);
            String name = (c.name() == null || c.name().isBlank()) ? "db" + (i + 1) : c.name().trim();
            if (map.containsKey(name)) {
                throw new IllegalArgumentException("db-scout.connections에 같은 이름이 두 번 있습니다: " + name);
            }
            String url = c.url().trim();
            DbKind kind = DbKind.fromJdbcUrl(url);
            kind.validateUrl(url);
            String description = c.description() == null ? "" : c.description();
            MaskPolicy policy;
            try {
                policy = MaskPolicy.of(nonBlank(c.maskColumns()), nonBlank(c.unmaskColumns()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + name + "' 접속의 가리기 설정이 잘못됐습니다: " + e.getMessage(), e);
            }
            Set<String> hidden = nonBlank(c.hiddenTables()).stream()
                    .map(h -> {
                        String[] parts = h.strip().split("\\.");
                        return parts.length >= 2
                                ? Identifiers.normalize(parts[parts.length - 2]) + "." + Identifiers.normalize(parts[parts.length - 1])
                                : Identifiers.normalize(parts[0]);
                    })
                    .collect(Collectors.toUnmodifiableSet());
            map.put(name, new Entry(name, kind, description, false, url, c.username(), c.password(), policy, hidden));
        }
        this.entries = Collections.unmodifiableMap(map);
    }

    private static List<String> nonBlank(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }

    public List<Entry> list() {
        return new ArrayList<>(entries.values());
    }

    /**
     * 이름으로 접속을 찾는다. 이름을 비우면 첫 번째 접속.
     *
     * @throws IllegalArgumentException 없는 이름이면 (메시지에 사용 가능한 이름을 넣어 Claude가 고칠 수 있게 한다)
     */
    public Entry get(String name) {
        if (name == null || name.isBlank()) {
            return entries.values().iterator().next();
        }
        Entry entry = entries.get(name.trim());
        if (entry == null) {
            throw new IllegalArgumentException(
                    "'" + name + "' 접속이 없습니다. 사용 가능한 접속: " + String.join(", ", entries.keySet()));
        }
        return entry;
    }
}
