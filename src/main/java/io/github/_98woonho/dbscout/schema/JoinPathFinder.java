package io.github._98woonho.dbscout.schema;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import io.github._98woonho.dbscout.connection.DbKind;
import io.github._98woonho.dbscout.connection.Identifiers;
import io.github._98woonho.dbscout.schema.SchemaModel.ForeignKey;

/**
 * 외래키를 길로 보고 두 테이블 사이의 가장 짧은 조인 경로를 찾는다(너비 우선 탐색).
 *
 * <p>같은 길이의 경로가 여러 개면 모두 돌려준다(최대 {@code maxPaths}개). 예를 들어 film과 language 사이에는
 * language_id(원어)와 original_language_id(원작 언어) 두 갈래가 있어, 질문에 맞는 쪽을 고를 수 있게 해야 한다.
 */
public final class JoinPathFinder {

    /** 경로 하나: 지나는 테이블들, 조인 조건들, 바로 쓸 수 있는 FROM ... JOIN 절. */
    public record JoinPath(List<String> tables, List<String> conditions, String sql) {
    }

    private record Edge(String to, ForeignKey fk) {
    }

    private JoinPathFinder() {
    }

    /**
     * @param start 시작 테이블의 qualifiedName
     * @param goal  도착 테이블의 qualifiedName
     */
    public static List<JoinPath> find(List<ForeignKey> foreignKeys, String start, String goal,
                                      int maxDepth, int maxPaths, DbKind kind) {
        Map<String, List<Edge>> graph = new HashMap<>();
        for (ForeignKey fk : foreignKeys) {
            if (fk.fromTable().equals(fk.toTable())) continue; // 자기 자신을 가리키는 키는 경로에 쓰지 않는다
            graph.computeIfAbsent(fk.fromTable(), k -> new ArrayList<>()).add(new Edge(fk.toTable(), fk));
            graph.computeIfAbsent(fk.toTable(), k -> new ArrayList<>()).add(new Edge(fk.fromTable(), fk));
        }

        // 1) 시작점에서 각 테이블까지의 거리
        Map<String, Integer> dist = new LinkedHashMap<>();
        dist.put(start, 0);
        Deque<String> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int d = dist.get(cur);
            if (cur.equals(goal) || d >= maxDepth) continue;
            for (Edge e : graph.getOrDefault(cur, List.of())) {
                if (!dist.containsKey(e.to())) {
                    dist.put(e.to(), d + 1);
                    queue.add(e.to());
                }
            }
        }
        if (!dist.containsKey(goal)) return List.of();

        // 2) 거리가 1씩 늘어나는 간선만 따라가며 가장 짧은 경로들을 모은다
        List<List<Edge>> paths = new ArrayList<>();
        collect(graph, dist, start, goal, new ArrayList<>(), paths, maxPaths);
        return paths.stream().map(p -> toJoinPath(start, p, kind)).toList();
    }

    private static void collect(Map<String, List<Edge>> graph, Map<String, Integer> dist, String cur, String goal,
                                List<Edge> path, List<List<Edge>> out, int maxPaths) {
        if (out.size() >= maxPaths) return;
        if (cur.equals(goal)) {
            out.add(List.copyOf(path));
            return;
        }
        int d = dist.get(cur);
        for (Edge e : graph.getOrDefault(cur, List.of())) {
            Integer next = dist.get(e.to());
            if (next == null || next != d + 1 || next > dist.get(goal)) continue;
            path.add(e);
            collect(graph, dist, e.to(), goal, path, out, maxPaths);
            path.removeLast();
        }
    }

    private static JoinPath toJoinPath(String start, List<Edge> edges, DbKind kind) {
        List<String> tables = new ArrayList<>(List.of(start));
        List<String> conditions = new ArrayList<>();
        StringBuilder sql = new StringBuilder("FROM ").append(name(kind, start));
        String cur = start;
        for (Edge e : edges) {
            String next = e.to();
            ForeignKey fk = e.fk();
            // 외래키 방향에 맞춰 next.컬럼 = cur.컬럼 형태로 쓴다
            List<String> nextCols = fk.fromTable().equals(next) ? fk.fromColumns() : fk.toColumns();
            List<String> curCols = fk.fromTable().equals(next) ? fk.toColumns() : fk.fromColumns();
            StringJoiner on = new StringJoiner(" AND ");
            for (int i = 0; i < nextCols.size(); i++) {
                on.add(name(kind, next) + "." + Identifiers.readable(kind, nextCols.get(i))
                        + " = " + name(kind, cur) + "." + Identifiers.readable(kind, curCols.get(i)));
            }
            conditions.add(on.toString());
            sql.append("\nJOIN ").append(name(kind, next)).append(" ON ").append(on);
            tables.add(next);
            cur = next;
        }
        return new JoinPath(List.copyOf(tables), List.copyOf(conditions), sql.toString());
    }

    private static String name(DbKind kind, String qualified) {
        String[] parts = qualified.split("\\.", 2);
        return parts.length == 2
                ? Identifiers.readable(kind, parts[0]) + "." + Identifiers.readable(kind, parts[1])
                : Identifiers.readable(kind, qualified);
    }
}
