# db-scout-mcp

> 개발 중입니다.

AI 에이전트가 데이터베이스 구조를 파악하고 **읽기 전용으로 안전하게** 조회하게 해 주는 MCP(Model Context Protocol) 서버와 Claude Skill입니다.

- 쓰기 쿼리 차단, 조회 행 수 제한, 실행 시간 제한, 개인정보 컬럼 가리기
- SQLite, MySQL, PostgreSQL 지원 (JDBC)
- 설정 없이 실행하면 내장된 공개 샘플 DB **Sakila**(DVD 대여점 데이터)로 바로 써 볼 수 있습니다.
- 서버는 LLM을 호출하지 않습니다. DB만 조회하므로 운영 비용이 들지 않습니다.

## 빌드

Java 21이 필요합니다. Gradle은 설치하지 않아도 됩니다(Gradle Wrapper 포함).

```powershell
.\gradlew.bat build        # 빌드 + 테스트. 결과: build\libs\db-scout-mcp.jar
```

첫 빌드 때 Sakila 샘플 데이터(약 8.5MB)를 GitHub에서 받아 해시를 확인한 뒤 SQLite 파일로 만듭니다.

## 라이선스

MIT. 내장된 Sakila 샘플 DB의 출처와 라이선스는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 보세요.
