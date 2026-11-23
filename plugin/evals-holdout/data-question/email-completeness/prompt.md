---
tags: [data-question, holdout-v1]
max_turns: 25
timeout_seconds: 600
allowed_tools: [Skill, Read, mcp__plugin_db-scout_db-scout__list_connections, mcp__plugin_db-scout_db-scout__list_tables, mcp__plugin_db-scout_db-scout__describe_table, mcp__plugin_db-scout_db-scout__search_schema, mcp__plugin_db-scout_db-scout__find_join_path, mcp__plugin_db-scout_db-scout__sample_rows, mcp__plugin_db-scout_db-scout__profile_column, mcp__plugin_db-scout_db-scout__explain_query, mcp__plugin_db-scout_db-scout__run_query]
---

고객 이메일 주소 자체는 보여주지 말고, 전체 고객 수와 이메일이 있는 고객 수, 없는 고객 수만 알려 줘. NULL이거나 공백을 제거한 값이 빈 문자열이면 이메일이 없는 것으로 세어 줘.

