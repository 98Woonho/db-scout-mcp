# db-scout-mcp

AI 에이전트가 데이터베이스 구조를 파악하고 **읽기 전용으로 안전하게** 조회하는 MCP 서버와 Claude Skill.
이전 프로젝트 korea-transit-mcp(`C:\Users\WoonHo\Desktop\mcp`)의 구조(plugin/, showcase/, 평가 방식)를 이어받는다.

## 작업 방식 (반드시 지킨다)

### git · 버전
- `git commit`, `git push`, 태그, 버전 올리기는 **사용자가 직접** 한다. Claude는 실행하지 않는다.
- 작업 단위가 끝나면 **한국어 커밋 메시지를 PowerShell 명령으로만** 제시한다. 예:
  ```powershell
  git add -A
  git commit -m "SQL 검사기 추가: SELECT 외 쿼리 차단"
  ```
  여러 줄이면 작은따옴표 here-string(`@'...'@`, 닫는 `'@`는 줄 맨 앞)을 쓴다.

### 비용
- **서버는 LLM을 호출하지 않는다.** 서버는 DB만 조회한다. 판단은 사용자의 Claude가 하므로 운영 비용은 0원이다.
- 비용이 드는 실행(`claude plugin eval` 등 실제 Claude를 돌리는 것)은 **예상 비용을 먼저 말하고 사용자 확인을 받은 뒤** 실행한다.
  - 참고 실측(korea-transit-mcp): 문제 6개 × 1회 약 $1, 문제 1개 약 $0.2.
- 무료 실행(단위 테스트, 로컬 빌드, 로컬 DB 점검)은 확인 없이 해도 된다.

### 보안 · 개인정보
- API 키, 비밀번호, 회사 내부 데이터·코드를 **쓰거나 노출하지 않는다.** 예제·테스트·데모는 공개 샘플 DB(Sakila)만 쓴다.
- DB 접속 정보는 환경변수나 플러그인 설정(sensitive)으로만 받는다. 저장소, 로그, 도구 응답에 넣지 않는다.
- 사용자의 Claude 설정(`~/.claude`, 전역 settings, 설치된 플러그인)은 바꾸지 않는다.

### 설명 방식
- 설명은 **쉬운 한국어**로 한다. 전문 용어는 처음 나올 때 풀어서 쓴다. 예: "JDBC(자바 프로그램이 DB에 접속하는 표준 방법)".
- README, 커밋 메시지, Skill 문서, 도구 설명(사용자에게 보이는 부분)도 한국어로 쓴다. 코드 식별자는 영어.

## 기술 스택
- Java 21, Spring Boot 4.1.2, Spring AI 2.0.2(MCP Server, STDIO), Gradle Wrapper 9.9.1(`gradlew.bat`)
- MCP 도구는 `@McpTool`(`org.springframework.ai.mcp.annotation`)로 만든다. 모든 도구에 `readOnlyHint = true`.
- SQL 분석: JSqlParser 5.4 / 테스트: JUnit 5, AssertJ, MCP Java SDK 클라이언트(실제 jar를 STDIO로 실행)
- DB: 개발·테스트·데모는 SQLite + Sakila 샘플. MySQL·PostgreSQL은 JDBC 드라이버로 지원.
- 패키지: `io.github._98woonho.dbscout` (GitHub 이름이 숫자로 시작해 Java 규칙대로 앞에 `_`를 붙임)
- Sakila는 저장소에 넣지 않는다. 빌드 때 jOOQ/sakila 고정 커밋에서 받아 SHA-256으로 확인한 뒤 `sakila.db`로 만들어 jar에 넣는다(`build.gradle.kts`의 `buildSakilaDb`). 출처 고지는 `THIRD_PARTY_NOTICES.md`.

## 결정 사항
- 파일형 DB는 SQLite. (2026-11-17)
- 민감 컬럼을 WHERE·ORDER BY·GROUP BY에 쓰는 것은 막지 않는다. 마스킹의 목적은 개인정보가 Claude의 대화로 흘러 들어가지 않게 하는 것이고, 결과로 나가는 값은 원래 컬럼을 추적해 가린다. 이 한계는 README에 적는다. (2026-11-17)
- 도구 9개 + Skill 2개(`db-explore`, `data-question`)로 시작한다. (2026-11-17)
- 개인정보 기본 규칙은 컬럼 이름 끝(email, phone, password, address, birth 등)으로 판단한다. 이름(first_name)은 배우처럼 공개 정보일 수 있어 기본 규칙에 넣지 않고, 접속별 `mask-columns`에 `테이블.컬럼`으로 지정한다. 데모 DB는 고객·직원 이름을 가린다(`SakilaDemo.MASK_COLUMNS`). (2026-11-17)
- 함수·연산을 거친 개인정보 값은 형식과 상관없이 '일부 가림'으로 처리하고(CASE 안에서 쓰여도 가림), `COUNT`만 예외로 둔다. 결과 컬럼의 출처를 확정하지 못하면, 개인정보를 건드린 쿼리의 모든 컬럼을 가린다. (2026-11-17)
- 행 수 제한은 SQL을 고치지 않고 JDBC `setMaxRows`로 건다(검사한 SQL을 그대로 실행하기 위해). (2026-11-17)

- 숨긴 테이블(`hidden-tables`)은 구조 도구에서 빠지고, 조회 SQL에 나오면 검사 단계에서 막는다. 스키마를 모르면 `아무스키마.테이블` 설정도 맞는 것으로 본다(더 많이 막는 쪽). 시스템 카탈로그(information_schema 등)로 구조를 보는 것까지 막지는 않는다. (2026-11-17)
- 도구는 결과를 `CallToolResult`로 직접 만든다(`ToolResults`). 예상한 실패는 이유를 그대로, 예상하지 못한 예외는 종류만 알린다(메시지에 데이터가 섞일 수 있어서). (2026-11-17)
- 도구 인자 이름은 camelCase(korea-transit과 같음): `table`, `fromTable`, `maxRows`, `connection` 등. (2026-11-17)

## 알아 둘 사실
- jOOQ판 Sakila(SQLite)의 `address.phone`은 603개 모두 공백 한 칸이다. 전화번호 가리기 테스트는 값을 직접 만들어 확인한다.
- jOOQ판 Sakila(SQLite)에는 `actor_info` 뷰가 없다(MySQL 전용 문법이라 주석 처리됨). 테이블 16개 + 뷰 5개.
- Sakila 정답 예: 카테고리별 영화 수 1위 Sports 74편, film.rating 최빈값 PG-13 223편, 결제 16,049건.
- `claude plugin eval`의 정규식은 JavaScript 문법이다. `(?i)` 인라인 플래그를 쓰면 채점기가 오류를 낸다(regex 채점은 `flags: i`, input_match는 `[Ss][Uu][Mm]`). PluginFilesTest가 막는다.
- 평가 결과(2026-11-17, v0.1.0, 문제당 1회, Claude Opus 5.5, 채점 Haiku): **Skill 있음 30/30**($1.09, `results/2026-11-17T12-01-40-004Z`, 실행 기록 보존) / **Skill 없음 26/30**($0.84, AI 채점 1건 사람이 바로잡음: overview 04 무리 짓기는 실제로 했음). 첫 Skill 있음 실행($1.13)은 `(?i)` 채점기 오류로 무효 처리.
  - 차이: find_join_path로 조인 확인, 실행한 SQL 공개, describe_table로 구조 확인(Skill 없으면 0회), ER 다이어그램.
  - 안전 항목(가리기·쓰기 차단·대량 출력)은 서버가 강제해 Skill 유무와 무관하게 통과.
  - 한계: Sakila는 유명한 공개 DB라 Skill 없이도 Claude가 구조를 미리 안다.
  - 채점이 놓친 점(실행 기록 대조로 발견): ① 매장 매출에서 두 기준만 계산하고 "다른 기준으로 봐도 2번 매장이 1위"라고 단정(고객 등록 매장 기준은 1번이 1위). Skill 없음도 같은 단정. 결과에 없는 숫자("결제 5,016건")도 섞임. ② `SUM(email IS NOT NULL)` 같은 개수가 가리기 규칙 때문에 `5***`로 가려짐.

## 개발 환경 (이 PC)
- JDK 21 설치됨(`C:\Program Files\Java\jdk-21.0.1`), Node.js 있음.
- **Docker, MySQL 없음.** Testcontainers처럼 Docker가 필요한 방법은 로컬에서 쓰지 않는다.
  MySQL·PostgreSQL 통합 테스트는 GitHub Actions의 서비스 컨테이너에서 돌린다(`.github/workflows/ci.yml`).
- 통합 테스트(`src/test/java/.../it/`)는 `DB_SCOUT_IT_MYSQL_URL`·`DB_SCOUT_IT_POSTGRES_URL`(+ `_USER`, `_PASSWORD`)이 있을 때만 돈다.
  CI는 `DB_SCOUT_IT_REQUIRED=true`를 줘서 접속 정보가 빠지면 실패하게 한다. Sakila는 `scripts/ci/load-sakila.sh`로 넣는다.
- 로컬에서 미리 확인하려면 압축 파일형 MySQL·PostgreSQL을 스크래치 폴더에 받아 localhost로만 띄우고, 끝나면 프로세스를 끄고 지운다(사용자 승인 후, 2026-11-17에 한 번 함).
- 셸은 PowerShell. 빌드: `.\gradlew.bat build`, 테스트: `.\gradlew.bat test`.

## 플러그인·평가
- 플러그인: `plugin/`(Skill 2개, 실행기 `bin/Launch.java`, 평가 `evals/`). 마켓플레이스: `.claude-plugin/marketplace.json`.
- 버전은 `gradle.properties` 한 곳. `plugin/.claude-plugin/plugin.json`의 version과 같아야 한다(`PluginFilesTest`). 태그 `v<버전>` push → `release.yml`이 jar와 `.sha256`을 GitHub Release에 올린다.
- 평가 파일 형식(YAML, 정규식, 도구 이름)은 `PluginFilesTest`가 무료로 검사한다. 평가 실행 전에 먼저 돌린다.
- 평가 실행: `.\scripts\eval.ps1`(비용 발생, 사용자 확인 후). CLI는 PATH에 없고 VS Code 확장 안에 있다: `$env:CLAUDE_BIN` = `C:\Users\WoonHo\.vscode\extensions\anthropic.claude-code-<버전>-win32-x64\resources\native-binary\claude.exe`.
- 평가 정답(Sakila): 카테고리 매출 Sports 5,314.21 … Comedy 4,383.58 / 최다 대여 고객 148번 46회 / 대여료 0.99 영화 341편 / 대여 16,044건 / 매장 매출은 기준에 따라 1위가 바뀜(재고 매장·결제 직원 매장: 2번, 고객 등록 매장: 1번).

## 설계 원칙
- **계산은 도구에, 판단은 Skill에.** 조인 경로 찾기, 행 수 제한, 마스킹처럼 답이 하나로 정해지는 일은 서버가 하고 테스트로 검증한다. "어떤 테이블부터 볼지", "결과를 어떻게 설명할지"는 Skill 문서에 둔다.
- **안전장치는 여러 겹으로.** SQL 검사 + 읽기 전용 연결 + 시간·행 수 제한 + 마스킹. 하나가 뚫려도 다음 겹이 막는다. README에는 "가장 확실한 방법은 읽기 전용 DB 계정"이라고 분명히 쓴다.
- **검사한 SQL을 그대로 실행한다.** `SqlGuard`가 주석을 지운 SQL(`CheckedQuery.sql()`)을 돌려주며, DB에는 반드시 이것을 보낸다. 원본 SQL을 보내면 분석기와 DB가 주석을 다르게 읽는 틈(MySQL `/*! */` 등)이 다시 생긴다.
- **모르면 막는다.** 분석하지 못한 SQL, 검사기가 끝까지 훑지 못한 구문은 실행하지 않는다. 새 우회 방법을 찾으면 `SqlGuardTest`의 사례표에 먼저 추가한다.
- **STDIO 주의:** MCP 통신이 표준출력(stdout)을 쓰므로 배너·로그를 stdout에 내보내지 않는다(로그는 stderr 또는 파일).

## 포트폴리오 연동
- 포트폴리오(`C:\Users\WoonHo\Desktop\98Woonho.github.io`)는 빌드 때 이 저장소의 `showcase/showcase.json`, `showcase/demos/*.json`을 가져간다.
- 포트폴리오의 `scripts/build-showcase.mjs`는 현재 npm 패키지만 실행할 수 있다. Java 서버 실행 방식을 추가해야 하며, 포트폴리오 쪽 수정도 사용자 확인 후 진행한다.
- 데모(`showcase/demos/*.json`)는 실제 실행 기록을 바탕으로 만들고, 캡션에 날짜·모델·버전을 적는다.
