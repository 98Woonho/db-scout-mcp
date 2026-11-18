package io.github._98woonho.dbscout.mask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsBooleanExpression;
import net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.IsUnknownExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.RegExpMatchOperator;
import net.sf.jsqlparser.expression.operators.relational.SimilarToExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * 결과의 각 컬럼이 어떤 원본 컬럼에서 왔는지 추적해, 개인정보에서 나온 컬럼을 찾는다.
 *
 * <p>별칭({@code email AS e}), 함수({@code UPPER(email)}), 서브쿼리·WITH·UNION, {@code SELECT *},
 * PostgreSQL의 행 전체 참조({@code SELECT c FROM customer c})를 거쳐도 원본을 따라간다.
 * {@code COUNT(...)}는 개수만 돌려주므로 가리지 않는다.
 *
 * <p>WHERE·ORDER BY·GROUP BY에 쓰인 컬럼은 결과 값으로 나오지 않으므로 보지 않는다(설계 결정).
 * SELECT 안이라도 참/거짓만 내는 조건({@code email IS NOT NULL}, {@code email LIKE '%.org'} 등)과
 * CASE의 WHEN 조건은 같은 이유로 보지 않는다. CASE는 THEN·ELSE로 나가는 값만 본다.
 * 확신할 수 없으면 더 많이 가리는 쪽을 택한다.
 */
public final class LineageAnalyzer {

    /**
     * 결과 컬럼 하나. kind가 null이면 가리지 않는다. derived는 원본 값이 함수·연산을 거쳤는지.
     *
     * @param conditionOnly 값 자리에 컬럼이 하나도 없고, 개인정보 컬럼은 조건·COUNT 안에서만 쓰였는지.
     *                      예: {@code SUM(email IS NOT NULL) AS has_email}. 이때는 별칭이 email로 끝나도
     *                      값이 개수·참거짓이므로 별칭 규칙으로 가리지 않는다.
     */
    public record OutputColumn(String name, MaskKind kind, boolean derived, boolean conditionOnly) {
        public OutputColumn(String name, MaskKind kind, boolean derived) {
            this(name, kind, derived, false);
        }

        OutputColumn renamed(String newName) {
            return new OutputColumn(newName, kind, derived, conditionOnly);
        }
    }

    /**
     * 분석 결과.
     *
     * @param outputs 결과 컬럼 순서대로의 판정. 위치를 확정하지 못했으면 null.
     * @param touched 쿼리가 결과로 내보내는 값 중 개인정보가 있었다면 그중 가장 강한 방식(없으면 null).
     */
    public record MaskPlan(List<OutputColumn> outputs, MaskKind touched) {
    }

    private static final Set<String> COUNT_FUNCTIONS = Set.of("count", "count_big");

    private final MaskPolicy policy;
    private final SchemaLookup schema;
    private boolean uncertain;
    private MaskKind touched;

    public LineageAnalyzer(MaskPolicy policy, SchemaLookup schema) {
        this.policy = policy;
        this.schema = schema;
    }

    public MaskPlan plan(Select select) {
        List<OutputColumn> outputs;
        try {
            outputs = analyzeSelect(select, null, Map.of());
        } catch (RuntimeException e) {
            // 분석기가 모르는 구문: 위치별 판정을 포기하고 보수적으로 처리한다
            uncertain = true;
            touched = MaskKind.strongest(touched, MaskKind.SECRET);
            outputs = null;
        }
        return new MaskPlan(uncertain ? null : outputs, touched);
    }

    // ───────────────────────── SELECT 구조 ─────────────────────────

    private List<OutputColumn> analyzeSelect(Select select, Scope outer, Map<String, List<OutputColumn>> ctes) {
        Map<String, List<OutputColumn>> env = withCtes(select, outer, ctes);
        return switch (select) {
            case PlainSelect ps -> analyzePlain(ps, outer, env);
            case SetOperationList sol -> analyzeSetOperation(sol, outer, env);
            case ParenthesedSelect ps -> analyzeSelect(ps.getSelect(), outer, env);
            default -> {
                uncertain = true;
                yield List.of();
            }
        };
    }

    private Map<String, List<OutputColumn>> withCtes(Select select, Scope outer, Map<String, List<OutputColumn>> ctes) {
        List<WithItem<?>> items = select.getWithItemsList();
        if (items == null || items.isEmpty()) return ctes;
        Map<String, List<OutputColumn>> env = new HashMap<>(ctes);
        for (WithItem<?> item : items) {
            ParenthesedSelect body = item.getSelect();
            if (body == null) continue; // 쓰기 WITH는 검사기가 이미 막는다
            String name = norm(item.getUnquotedAliasName());
            env.put(name, List.of()); // 재귀 WITH의 자기 참조: 첫 번째는 비어 있다고 보고
            List<OutputColumn> cols = analyzeSelect(body.getSelect(), outer, env);
            env.put(name, rename(cols, item.getWithItemList()));
            cols = analyzeSelect(body.getSelect(), outer, env); // 두 번째에 자기 참조 결과를 반영
            env.put(name, rename(cols, item.getWithItemList()));
        }
        return env;
    }

    private List<OutputColumn> analyzeSetOperation(SetOperationList sol, Scope outer, Map<String, List<OutputColumn>> env) {
        List<OutputColumn> result = null;
        for (Select branch : sol.getSelects()) {
            List<OutputColumn> cols = analyzeSelect(branch, outer, env);
            if (result == null) {
                result = new ArrayList<>(cols);
                continue;
            }
            if (cols.size() != result.size()) {
                uncertain = true;
                continue;
            }
            for (int i = 0; i < result.size(); i++) {
                OutputColumn a = result.get(i);
                OutputColumn b = cols.get(i);
                result.set(i, new OutputColumn(a.name(), MaskKind.strongest(a.kind(), b.kind()),
                        a.derived() || b.derived(), a.conditionOnly() && b.conditionOnly()));
            }
        }
        return result == null ? List.of() : result;
    }

    private List<OutputColumn> analyzePlain(PlainSelect ps, Scope outer, Map<String, List<OutputColumn>> env) {
        Scope scope = new Scope(outer);
        addFromItem(ps.getFromItem(), scope, env);
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) addFromItem(join.getFromItem(), scope, env);
        }

        List<OutputColumn> outputs = new ArrayList<>();
        for (SelectItem<?> item : ps.getSelectItems()) {
            Expression e = item.getExpression();
            if (e instanceof AllTableColumns atc) {
                Source src = scope.find(norm(atc.getTable().getName()));
                if (src == null || src.columns == null) {
                    uncertain = true;
                } else {
                    src.columns.forEach(this::touch);
                    outputs.addAll(src.columns);
                }
            } else if (e instanceof AllColumns) {
                for (Source src : scope.sources) {
                    if (src.columns == null) {
                        uncertain = true;
                    } else {
                        src.columns.forEach(this::touch);
                        outputs.addAll(src.columns);
                    }
                }
            } else {
                KindCollector kc = new KindCollector(scope, env);
                e.accept(kc, null);
                String name = item.getAlias() != null ? item.getAlias().getName()
                        : e instanceof Column c ? c.getColumnName() : e.toString();
                boolean derived = !(e instanceof Column) || kc.derived;
                boolean conditionOnly = kc.kind == null && !kc.valueColumns && kc.tested;
                OutputColumn out = new OutputColumn(name, kc.kind, derived, conditionOnly);
                touch(out);
                outputs.add(out);
            }
        }
        return outputs;
    }

    private void addFromItem(FromItem item, Scope scope, Map<String, List<OutputColumn>> env) {
        if (item == null) return;
        if (item.getPivot() != null || item.getUnPivot() != null) uncertain = true;
        Alias alias = item.getAlias();
        String aliasName = alias == null ? null : norm(alias.getName());

        switch (item) {
            case Table t -> {
                String name = norm(t.getName());
                Source src;
                if (t.getSchemaName() == null && env.containsKey(name)) {
                    src = new Source(aliasName != null ? aliasName : name, null, env.get(name), null);
                } else {
                    List<String> names = schema.columns(t.getSchemaName(), t.getName());
                    List<OutputColumn> cols = names.isEmpty() ? null : names.stream()
                            .map(c -> new OutputColumn(c, policy.kindFor(name, c), false))
                            .toList();
                    src = new Source(aliasName != null ? aliasName : name, name, cols, null);
                }
                scope.sources.add(src.renamed(alias));
            }
            case ParenthesedSelect ps -> {
                // LATERAL 서브쿼리는 앞쪽 FROM을 참조할 수 있으므로 현재 범위를 넘긴다
                List<OutputColumn> cols = analyzeSelect(ps.getSelect(), scope, env);
                scope.sources.add(new Source(aliasName, null, cols, null).renamed(alias));
            }
            case ParenthesedFromItem pfi -> {
                addFromItem(pfi.getFromItem(), scope, env);
                if (pfi.getJoins() != null) {
                    for (Join join : pfi.getJoins()) addFromItem(join.getFromItem(), scope, env);
                }
            }
            case TableFunction tf -> {
                // generate_series(1, 10) 처럼 컬럼을 모르는 출처. 인자에 개인정보 컬럼이 쓰였으면 결과도 개인정보로 본다
                KindCollector kc = new KindCollector(scope, env);
                tf.getFunction().accept(kc, null);
                scope.sources.add(new Source(aliasName, null, null, kc.kind));
            }
            default -> {
                uncertain = true;
                scope.sources.add(new Source(aliasName, null, null, null));
            }
        }
    }

    // ───────────────────────── 컬럼 참조 해석 ─────────────────────────

    private record Resolved(MaskKind kind, boolean derived) {
    }

    private Resolved resolve(Column column, Scope scope) {
        String col = norm(column.getColumnName());
        Table qualifier = column.getTable();
        if (qualifier != null && qualifier.getName() != null) {
            String q = norm(qualifier.getName());
            for (Scope s = scope; s != null; s = s.parent) {
                Source src = s.byAlias(q);
                if (src != null) return src.column(col, policy);
            }
            return new Resolved(policy.kindFor(q, col), false);
        }

        for (Scope s = scope; s != null; s = s.parent) {
            boolean found = false;
            MaskKind kind = null;
            boolean derived = false;
            for (Source src : s.sources) {
                if (src.columns != null) {
                    OutputColumn oc = src.find(col);
                    if (oc != null) {
                        found = true;
                        kind = MaskKind.strongest(kind, oc.kind());
                        derived |= oc.derived() && oc.kind() != null;
                    }
                } else {
                    MaskKind k = MaskKind.strongest(policy.kindFor(src.table, col), src.taint);
                    if (k != null) {
                        found = true;
                        kind = MaskKind.strongest(kind, k);
                        derived |= src.taint != null;
                    }
                }
            }
            if (found) return new Resolved(kind, derived);
            // PostgreSQL: 테이블 별칭을 컬럼처럼 쓰면 행 전체가 하나의 값으로 나온다 (SELECT c FROM customer c)
            Source row = s.byAlias(col);
            if (row != null) return new Resolved(row.strongestKind(), true);
        }
        return new Resolved(policy.kindFor(null, col), false);
    }

    private void touch(OutputColumn column) {
        touched = MaskKind.strongest(touched, column.kind());
    }

    /** 식 하나를 훑어 그 안에서 쓰인 개인정보 컬럼 중 가장 강한 방식을 모은다. */
    private final class KindCollector extends ExpressionVisitorAdapter<Void> {
        private final Scope scope;
        private final Map<String, List<OutputColumn>> env;
        MaskKind kind;
        boolean derived;
        /** 값 자리(조건·COUNT 밖)에 컬럼·별표·서브쿼리가 하나라도 있었는지. */
        boolean valueColumns;
        /** 조건·COUNT 안에서 개인정보 컬럼이 쓰였는지. */
        boolean tested;

        KindCollector(Scope scope, Map<String, List<OutputColumn>> env) {
            this.scope = scope;
            this.env = env;
        }

        private void add(MaskKind k, boolean d) {
            kind = MaskKind.strongest(kind, k);
            derived |= d && k != null;
        }

        /** 값으로 나가지 않는 식(조건, COUNT 인자): 들어가 보기만 하고 결과에는 더하지 않는다. */
        private Void tested(Expression e) {
            if (e == null) return null;
            ConditionProbe probe = new ConditionProbe(scope);
            e.accept(probe, null);
            tested |= probe.personal;
            return null;
        }

        @Override
        public <S> Void visit(Column column, S context) {
            valueColumns = true;
            Resolved r = resolve(column, scope);
            add(r.kind(), r.derived());
            return null;
        }

        @Override
        public <S> Void visit(Function function, S context) {
            if (COUNT_FUNCTIONS.contains(norm(function.getName()))) { // 개수만 돌려준다
                return tested(function.getParameters());
            }
            return super.visit(function, context);
        }

        @Override
        public <S> Void visit(AnalyticExpression a, S context) {
            // 창 함수는 인자만 값으로 흘러간다(PARTITION BY·ORDER BY는 WHERE처럼 결과 값이 아님)
            if (COUNT_FUNCTIONS.contains(norm(a.getName()))) return tested(a.getExpression());
            if (a.getExpression() != null) a.getExpression().accept(this, context);
            if (a.getDefaultValue() != null) a.getDefaultValue().accept(this, context);
            return null;
        }

        // ── 참/거짓만 내는 조건: WHERE와 같게 본다. 결과는 참·거짓(또는 1·0·NULL)뿐이라 원래 값이 나가지 않는다.
        //    SUM(email IS NOT NULL), COUNT(CASE WHEN email LIKE '%.org' THEN 1 END) 등은 가리지 않는다.

        @Override
        public <S> Void visit(EqualsTo e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(NotEqualsTo e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(GreaterThan e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(GreaterThanEquals e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(MinorThan e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(MinorThanEquals e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(LikeExpression e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(SimilarToExpression e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(RegExpMatchOperator e, S context) { // PostgreSQL ~, ~*
            return tested(e);
        }

        @Override
        public <S> Void visit(IsNullExpression e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(IsBooleanExpression e, S context) { // IS TRUE, IS FALSE
            return tested(e);
        }

        @Override
        public <S> Void visit(IsUnknownExpression e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(IsDistinctExpression e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(InExpression e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(Between e, S context) {
            return tested(e);
        }

        @Override
        public <S> Void visit(ExistsExpression e, S context) {
            return tested(e);
        }

        /** CASE는 결과로 나가는 THEN·ELSE 값만 본다. CASE 뒤의 식과 WHEN 조건은 비교에만 쓰인다. */
        @Override
        public <S> Void visit(CaseExpression c, S context) {
            tested(c.getSwitchExpression());
            if (c.getWhenClauses() != null) {
                for (WhenClause w : c.getWhenClauses()) w.accept(this, context);
            }
            if (c.getElseExpression() != null) c.getElseExpression().accept(this, context);
            return null;
        }

        @Override
        public <S> Void visit(WhenClause w, S context) {
            tested(w.getWhenExpression());
            if (w.getThenExpression() != null) w.getThenExpression().accept(this, context);
            return null;
        }

        @Override
        public <S> Void visit(AllTableColumns atc, S context) { // row_to_json(c.*) 등
            valueColumns = true;
            Source src = scope.find(norm(atc.getTable().getName()));
            add(src == null ? MaskKind.SECRET : src.strongestKind(), true);
            return null;
        }

        @Override
        public <S> Void visit(AllColumns all, S context) {
            valueColumns = true;
            for (Source src : scope.sources) add(src.strongestKind(), true);
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) { // 스칼라 서브쿼리, IN·EXISTS
            subquery(select);
            return null;
        }

        @Override
        public <S> Void visit(Select select, S context) {
            subquery(select);
            return null;
        }

        private void subquery(Select select) {
            valueColumns = true;
            for (OutputColumn oc : analyzeSelect(select, scope, env)) add(oc.kind(), true);
        }
    }

    /**
     * 조건 안에 개인정보 컬럼이 쓰였는지만 본다(별칭 규칙을 건너뛸지 정할 때 씀).
     * 서브쿼리 안은 보지 않는다. 그러면 personal이 false로 남아 별칭 규칙이 그대로 적용된다(더 많이 가리는 쪽).
     */
    private final class ConditionProbe extends ExpressionVisitorAdapter<Void> {
        private final Scope scope;
        boolean personal;

        ConditionProbe(Scope scope) {
            this.scope = scope;
        }

        @Override
        public <S> Void visit(Column column, S context) {
            personal |= resolve(column, scope).kind() != null;
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            return null;
        }

        @Override
        public <S> Void visit(Select select, S context) {
            return null;
        }
    }

    // ───────────────────────── 범위(FROM에 나온 출처들) ─────────────────────────

    private static final class Scope {
        final Scope parent;
        final List<Source> sources = new ArrayList<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        Source byAlias(String name) {
            for (Source s : sources) {
                if (name.equals(s.alias)) return s;
            }
            return null;
        }

        Source find(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                Source src = s.byAlias(name);
                if (src != null) return src;
            }
            return null;
        }
    }

    /**
     * FROM에 나온 출처 하나.
     *
     * @param alias   참조할 때 쓰는 이름(별칭 또는 테이블 이름)
     * @param table   실제 테이블 이름(서브쿼리·함수면 null)
     * @param columns 알고 있는 컬럼(모르면 null)
     * @param taint   컬럼을 모르는 출처가 개인정보에서 만들어졌으면 그 방식
     */
    private record Source(String alias, String table, List<OutputColumn> columns, MaskKind taint) {

        OutputColumn find(String column) {
            for (OutputColumn c : columns) {
                if (norm(c.name()).equals(column)) return c;
            }
            return null;
        }

        Resolved column(String column, MaskPolicy policy) {
            if (columns != null) {
                OutputColumn oc = find(column);
                return oc == null ? new Resolved(policy.kindFor(table, column), false)
                        : new Resolved(oc.kind(), oc.derived());
            }
            return new Resolved(MaskKind.strongest(policy.kindFor(table, column), taint), taint != null);
        }

        MaskKind strongestKind() {
            if (columns == null) {
                // 컬럼을 모르는 실제 테이블의 행 전체는 무엇이 들었는지 알 수 없으므로 전부 가린다
                return table != null ? MaskKind.SECRET : taint;
            }
            MaskKind k = taint;
            for (OutputColumn c : columns) k = MaskKind.strongest(k, c.kind());
            return k;
        }

        /** {@code AS t(a, b)}처럼 별칭에 컬럼 이름이 있으면 위치대로 이름을 바꾼다. */
        Source renamed(Alias alias) {
            if (alias == null || alias.getAliasColumns() == null || alias.getAliasColumns().isEmpty() || columns == null) {
                return this;
            }
            List<OutputColumn> renamed = new ArrayList<>(columns);
            for (int i = 0; i < Math.min(renamed.size(), alias.getAliasColumns().size()); i++) {
                renamed.set(i, renamed.get(i).renamed(alias.getAliasColumns().get(i).name));
            }
            return new Source(this.alias, table, renamed, taint);
        }
    }

    private static List<OutputColumn> rename(List<OutputColumn> cols, List<SelectItem<?>> names) {
        if (names == null || names.isEmpty()) return cols;
        List<OutputColumn> renamed = new ArrayList<>(cols);
        for (int i = 0; i < Math.min(renamed.size(), names.size()); i++) {
            renamed.set(i, renamed.get(i).renamed(names.get(i).toString()));
        }
        return renamed;
    }

    static String norm(String identifier) {
        if (identifier == null) return null;
        String s = identifier.strip();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("`") && s.endsWith("`")
                || s.startsWith("[") && s.endsWith("]"))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }
}
