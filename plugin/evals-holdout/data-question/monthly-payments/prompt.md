---
tags: [data-question, holdout-v1]
max_turns: 25
timeout_seconds: 600
allowed_tools: [Skill, Read, mcp__plugin_db-scout_db-scout__list_connections, mcp__plugin_db-scout_db-scout__list_tables, mcp__plugin_db-scout_db-scout__describe_table, mcp__plugin_db-scout_db-scout__search_schema, mcp__plugin_db-scout_db-scout__find_join_path, mcp__plugin_db-scout_db-scout__sample_rows, mcp__plugin_db-scout_db-scout__profile_column, mcp__plugin_db-scout_db-scout__explain_query, mcp__plugin_db-scout_db-scout__run_query]
---

2005년 7월에 받은 결제 금액 합계, 결제 건수, 결제한 서로 다른 고객 수를 고객의 등록 매장별로 알려 줘. 결제일은 7월 1일 이상, 8월 1일 미만으로 계산해 줘.

