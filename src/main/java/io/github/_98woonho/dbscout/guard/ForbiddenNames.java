package io.github._98woonho.dbscout.guard;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SELECT 안에서도 쓰면 안 되는 함수와 테이블.
 *
 * <p>SELECT 문이라도 함수 호출로 서버 파일을 읽거나, 다른 SQL을 실행하거나, 설정을 바꿀 수 있다.
 * 읽기 전용 연결(2겹째 안전장치)이 막는 것도 있지만, 여기서 먼저 막아 이유를 분명히 알려 준다.
 */
final class ForbiddenNames {

    private ForbiddenNames() {
    }

    enum Category {
        DELAY("실행을 일부러 멈추거나 부하를 주는 함수"),
        FILE("DB 서버의 파일이나 운영체제에 접근하는 함수"),
        REMOTE("다른 DB에 접속하는 함수"),
        DYNAMIC_SQL("문자열로 받은 SQL을 실행하거나 테이블 전체를 꺼내 안전 검사를 우회할 수 있는 함수"),
        SIDE_EFFECT("DB 설정·세션·잠금·시퀀스처럼 상태를 바꾸는 함수");

        final String description;

        Category(String description) {
            this.description = description;
        }
    }

    private static final Map<String, Category> FUNCTIONS = Map.ofEntries(
            // 지연·부하
            Map.entry("sleep", Category.DELAY),
            Map.entry("pg_sleep", Category.DELAY),
            Map.entry("pg_sleep_for", Category.DELAY),
            Map.entry("pg_sleep_until", Category.DELAY),
            Map.entry("benchmark", Category.DELAY),
            Map.entry("master_pos_wait", Category.DELAY),
            Map.entry("source_pos_wait", Category.DELAY),
            Map.entry("wait_for_executed_gtid_set", Category.DELAY),
            Map.entry("wait_until_sql_thread_after_gtids", Category.DELAY),
            // 파일·OS
            Map.entry("load_file", Category.FILE),
            Map.entry("load_extension", Category.FILE),
            Map.entry("readfile", Category.FILE),
            Map.entry("writefile", Category.FILE),
            Map.entry("edit", Category.FILE),
            Map.entry("fts3_tokenizer", Category.FILE),
            Map.entry("sys_exec", Category.FILE),
            Map.entry("sys_eval", Category.FILE),
            Map.entry("pg_stat_file", Category.FILE),
            Map.entry("loread", Category.FILE),
            Map.entry("lowrite", Category.FILE),
            // 다른 SQL 실행·테이블 통째로 꺼내기
            Map.entry("query_to_xml", Category.DYNAMIC_SQL),
            Map.entry("query_to_xmlschema", Category.DYNAMIC_SQL),
            Map.entry("query_to_xml_and_xmlschema", Category.DYNAMIC_SQL),
            Map.entry("cursor_to_xml", Category.DYNAMIC_SQL),
            Map.entry("cursor_to_xmlschema", Category.DYNAMIC_SQL),
            Map.entry("table_to_xml", Category.DYNAMIC_SQL),
            Map.entry("table_to_xmlschema", Category.DYNAMIC_SQL),
            Map.entry("table_to_xml_and_xmlschema", Category.DYNAMIC_SQL),
            Map.entry("schema_to_xml", Category.DYNAMIC_SQL),
            Map.entry("schema_to_xmlschema", Category.DYNAMIC_SQL),
            Map.entry("schema_to_xml_and_xmlschema", Category.DYNAMIC_SQL),
            Map.entry("database_to_xml", Category.DYNAMIC_SQL),
            Map.entry("database_to_xmlschema", Category.DYNAMIC_SQL),
            Map.entry("database_to_xml_and_xmlschema", Category.DYNAMIC_SQL),
            Map.entry("ts_stat", Category.DYNAMIC_SQL),
            Map.entry("eval", Category.DYNAMIC_SQL),
            // 상태 변경
            Map.entry("set_config", Category.SIDE_EFFECT),
            Map.entry("nextval", Category.SIDE_EFFECT),
            Map.entry("setval", Category.SIDE_EFFECT),
            Map.entry("txid_current", Category.SIDE_EFFECT),
            Map.entry("pg_current_xact_id", Category.SIDE_EFFECT),
            Map.entry("pg_notify", Category.SIDE_EFFECT),
            Map.entry("pg_reload_conf", Category.SIDE_EFFECT),
            Map.entry("pg_rotate_logfile", Category.SIDE_EFFECT),
            Map.entry("pg_terminate_backend", Category.SIDE_EFFECT),
            Map.entry("pg_cancel_backend", Category.SIDE_EFFECT),
            Map.entry("pg_promote", Category.SIDE_EFFECT),
            Map.entry("pg_switch_wal", Category.SIDE_EFFECT),
            Map.entry("pg_log_backend_memory_contexts", Category.SIDE_EFFECT),
            Map.entry("pg_import_system_collations", Category.SIDE_EFFECT),
            Map.entry("get_lock", Category.SIDE_EFFECT),
            Map.entry("release_lock", Category.SIDE_EFFECT),
            Map.entry("release_all_locks", Category.SIDE_EFFECT));

    /** 이 접두사로 시작하는 함수는 모두 막는다. */
    private static final Map<String, Category> FUNCTION_PREFIXES = Map.ofEntries(
            Map.entry("pg_read_", Category.FILE),        // pg_read_file, pg_read_binary_file
            Map.entry("pg_ls_", Category.FILE),          // pg_ls_dir, pg_ls_logdir ...
            Map.entry("pg_file_", Category.FILE),        // adminpack: pg_file_write ...
            Map.entry("lo_", Category.FILE),             // lo_import, lo_export, lo_get ...
            Map.entry("dblink", Category.REMOTE),        // dblink, dblink_exec, dblink_connect ...
            Map.entry("pg_advisory", Category.SIDE_EFFECT),
            Map.entry("pg_try_advisory", Category.SIDE_EFFECT),
            Map.entry("pg_stat_reset", Category.SIDE_EFFECT),
            Map.entry("pg_create_", Category.SIDE_EFFECT),       // 복원 지점, 복제 슬롯
            Map.entry("pg_drop_replication", Category.SIDE_EFFECT),
            Map.entry("pg_replication_", Category.SIDE_EFFECT),
            Map.entry("pg_logical_", Category.SIDE_EFFECT),
            Map.entry("pg_wal_replay_", Category.SIDE_EFFECT),
            Map.entry("pg_backup_", Category.SIDE_EFFECT),
            Map.entry("pg_start_backup", Category.SIDE_EFFECT),
            Map.entry("pg_stop_backup", Category.SIDE_EFFECT));

    /** 비밀번호 해시·접속 정보가 든 시스템 테이블. 이름만으로 판단(스키마 없이 써도 찾아지므로). */
    private static final Set<String> TABLES = Set.of(
            "pg_authid", "pg_shadow", "pg_user_mapping", "pg_user_mappings", "pg_largeobject",
            "sqlite_dbpage");

    /** 스키마까지 맞아야 막는 테이블(이름이 흔해서). */
    private static final Map<String, List<String>> SCHEMA_TABLES = Map.of(
            "mysql", List.of("user", "global_priv", "slave_master_info", "replication_connection_configuration"));

    static Optional<Category> function(String name) {
        String n = normalize(name);
        Category exact = FUNCTIONS.get(n);
        if (exact != null) return Optional.of(exact);
        return FUNCTION_PREFIXES.entrySet().stream()
                .filter(e -> n.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    static boolean table(String schema, String name) {
        String n = normalize(name);
        if (TABLES.contains(n)) return true;
        if (schema == null) return false;
        List<String> names = SCHEMA_TABLES.get(normalize(schema));
        return names != null && names.contains(n);
    }

    /** 따옴표를 벗기고 소문자로. "PG_SLEEP", `sleep`, [x] 모두 같은 이름으로 본다. */
    static String normalize(String identifier) {
        String s = identifier.strip();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']')) {
                s = s.substring(1, s.length() - 1);
            }
        }
        return s.toLowerCase(Locale.ROOT);
    }
}
