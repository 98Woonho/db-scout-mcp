# db-scout-mcp

AI 에이전트가 데이터베이스 구조를 파악하고 **읽기 전용으로 안전하게** 조회하는 MCP 서버입니다.
쓰기 쿼리 차단, 행 수·실행 시간 제한, 개인정보 컬럼 가리기를 서버가 강제합니다. MySQL, PostgreSQL, SQLite를 지원합니다.

**Node.js 20 이상과 Java 21 이상**이 필요합니다. 서버 본체는 Java 프로그램(jar)이고, 이 패키지는 실행기입니다.
처음 실행할 때 GitHub Release에서 같은 버전의 jar(약 40MB)를 받아 SHA-256 해시를 확인한 뒤 `~/.db-scout/jars/<버전>/`에 보관합니다.

```json
{
  "mcpServers": {
    "db-scout": {
      "command": "npx",
      "args": ["-y", "db-scout-mcp"],
      "env": {
        "DB_SCOUT_URL": "jdbc:postgresql://localhost:5432/shop",
        "DB_SCOUT_USERNAME": "readonly",
        "DB_SCOUT_PASSWORD": "<비밀번호>"
      }
    }
  }
}
```

접속 정보를 비워 두면 내장 샘플 DB(Sakila)로 실행됩니다. 설정, 도구, Claude Code 플러그인(Skill 포함)은 [GitHub 저장소](https://github.com/98Woonho/db-scout-mcp)를 보세요.

License: MIT
