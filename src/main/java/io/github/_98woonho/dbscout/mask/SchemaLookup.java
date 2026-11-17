package io.github._98woonho.dbscout.mask;

import java.util.List;

/** 테이블의 컬럼 이름 목록을 알려 준다. {@code SELECT *}를 펼치고, 행 전체 참조를 알아내는 데 쓴다. */
@FunctionalInterface
public interface SchemaLookup {

    /** 컬럼 이름(순서대로). 테이블을 찾지 못하면 빈 목록. */
    List<String> columns(String schema, String table);

    SchemaLookup NONE = (schema, table) -> List.of();
}
