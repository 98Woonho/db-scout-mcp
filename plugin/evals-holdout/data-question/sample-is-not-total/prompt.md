---
tags: [data-question, holdout-v1]
max_turns: 25
timeout_seconds: 600
allowed_tools: [Skill, Read, mcp__plugin_db-scout_db-scout__list_connections, mcp__plugin_db-scout_db-scout__list_tables, mcp__plugin_db-scout_db-scout__describe_table, mcp__plugin_db-scout_db-scout__search_schema, mcp__plugin_db-scout_db-scout__find_join_path, mcp__plugin_db-scout_db-scout__sample_rows, mcp__plugin_db-scout_db-scout__profile_column, mcp__plugin_db-scout_db-scout__explain_query, mcp__plugin_db-scout_db-scout__run_query]
---

대여 기록을 rental_id 순서로 앞의 5개만 조회해 줘. 그리고 그 5개가 전체 건수인지 별도로 확인해서 전체 대여 건수도 알려 줘.

