---
tags: [data-question, holdout-v1]
max_turns: 25
timeout_seconds: 600
allowed_tools: [Skill, Read, mcp__plugin_db-scout_db-scout__list_connections, mcp__plugin_db-scout_db-scout__list_tables, mcp__plugin_db-scout_db-scout__describe_table, mcp__plugin_db-scout_db-scout__search_schema, mcp__plugin_db-scout_db-scout__find_join_path, mcp__plugin_db-scout_db-scout__sample_rows, mcp__plugin_db-scout_db-scout__profile_column, mcp__plugin_db-scout_db-scout__explain_query, mcp__plugin_db-scout_db-scout__run_query]
---

2099년 한 해의 매출을 DB에 기록된 결제 데이터만으로 알려 줘. 거래가 없다면 예측값으로 채우지 말고 그 사실을 알려 줘.

