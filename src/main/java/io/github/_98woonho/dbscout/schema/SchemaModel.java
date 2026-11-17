package io.github._98woonho.dbscout.schema;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 구조 조회 결과에 쓰는 자료형. null 항목은 응답에서 뺀다(대화 공간 절약). */
public final class SchemaModel {

    private SchemaModel() {
    }

    /** 테이블·뷰 하나. schema는 SQLite면 null, MySQL이면 데이터베이스 이름. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Table(String schema, String name, String type, String comment) {

        public String qualifiedName() {
            return schema == null ? name : schema + "." + name;
        }

        public boolean isView() {
            return type != null && type.contains("VIEW");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Column(String name, String type, boolean nullable, String defaultValue, Boolean primaryKey,
                         String comment, String masked) {
    }

    public record Index(String name, List<String> columns, boolean unique) {
    }

    /** 외래키: fromTable.fromColumns → toTable.toColumns */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ForeignKey(String name, String fromTable, List<String> fromColumns,
                             String toTable, List<String> toColumns) {
    }
}
