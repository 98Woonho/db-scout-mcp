package io.github._98woonho.dbscout.guard;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.ForMode;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * SELECT 문 전체(서브쿼리, WITH, UNION, JOIN 조건, ORDER BY 등 모든 곳)를 훑어
 * 금지된 함수·테이블, INTO, 행 잠금, 쿼리 안의 쓰기 문장을 찾는다.
 *
 * <p>JSqlParser의 {@link TablesNamesFinder}는 문장의 모든 부분을 방문하므로, 이를 상속해 검사를 끼워 넣는다.
 * 방문하지 못하는 구문을 만나면 예외가 나고, 그때는 실행하지 않는다(모르면 막는다).
 */
final class SelectInspector extends TablesNamesFinder<Void> {

    private final Set<String> functions = new LinkedHashSet<>();

    /** 검사하고 참조한 테이블 이름을 돌려준다. */
    Set<String> inspect(Select select) {
        try {
            return getTables((Statement) select);
        } catch (QueryRejectedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new QueryRejectedException(RejectReason.PARSE_ERROR,
                    "안전 검사기가 이 구문을 끝까지 확인하지 못해 실행하지 않았습니다. 더 단순한 SQL로 바꿔 보세요.");
        }
    }

    Set<String> functions() {
        return functions;
    }

    @Override
    public <S> Void visit(Function function, S context) {
        List<String> parts = function.getMultipartName();
        String name = parts == null || parts.isEmpty() ? function.getName() : parts.getLast();
        functions.add(ForbiddenNames.normalize(name));
        ForbiddenNames.function(name).ifPresent(category -> {
            throw new QueryRejectedException(RejectReason.FORBIDDEN_FUNCTION,
                    ForbiddenNames.normalize(name) + "()는 " + category.description + "라서 쓸 수 없습니다.");
        });
        return super.visit(function, context);
    }

    @Override
    public <S> Void visit(Table table, S context) {
        if (ForbiddenNames.table(table.getSchemaName(), table.getName())) {
            throw new QueryRejectedException(RejectReason.FORBIDDEN_TABLE,
                    table.getFullyQualifiedName() + " 은(는) 비밀번호·접속 정보가 든 시스템 테이블이라 조회할 수 없습니다.");
        }
        return super.visit(table, context);
    }

    @Override
    public <S> Void visit(PlainSelect select, S context) {
        if ((select.getIntoTables() != null && !select.getIntoTables().isEmpty())
                || select.getIntoTempTable() != null
                || select.getMySqlSelectIntoClause() != null) {
            throw new QueryRejectedException(RejectReason.SELECT_INTO,
                    "SELECT ... INTO는 결과를 테이블·파일·변수에 저장하므로 쓸 수 없습니다. INTO 부분을 빼세요.");
        }
        checkLocking(select);
        return super.visit(select, context);
    }

    @Override
    public <S> Void visit(SetOperationList select, S context) {
        checkLocking(select);
        return super.visit(select, context);
    }

    @Override
    public <S> Void visit(ParenthesedSelect select, S context) {
        checkLocking(select);
        return super.visit(select, context);
    }

    @Override
    public <S> Void visit(Delete delete, S context) {
        throw writeInQuery("DELETE");
    }

    @Override
    public <S> Void visit(Update update, S context) {
        throw writeInQuery("UPDATE");
    }

    @Override
    public <S> Void visit(Insert insert, S context) {
        throw writeInQuery("INSERT");
    }

    @Override
    public <S> Void visit(Merge merge, S context) {
        throw writeInQuery("MERGE");
    }

    @Override
    public <S> Void visit(Upsert upsert, S context) {
        throw writeInQuery("UPSERT");
    }

    private static void checkLocking(Select select) {
        ForMode mode = select.getForMode();
        boolean locks = (mode != null && mode != ForMode.READ_ONLY && mode != ForMode.FETCH_ONLY)
                || select.getForUpdate() != null
                || select.getForUpdateTable() != null
                || (select.getForUpdateTables() != null && !select.getForUpdateTables().isEmpty())
                || select.isSkipLocked();
        if (locks) {
            throw new QueryRejectedException(RejectReason.LOCKING,
                    "FOR UPDATE / FOR SHARE / LOCK IN SHARE MODE는 행을 잠가 다른 작업을 막을 수 있어 쓸 수 없습니다. 잠금 절을 빼세요.");
        }
    }

    private static QueryRejectedException writeInQuery(String kind) {
        return new QueryRejectedException(RejectReason.WRITE_IN_QUERY,
                "쿼리 안에 " + kind + " 문이 들어 있습니다. 이 서버는 읽기 전용이라 데이터를 바꾸는 문장은 실행하지 않습니다.");
    }
}
