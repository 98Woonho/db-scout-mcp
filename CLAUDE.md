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
- Java 21, Spring Boot, Spring AI MCP Server(STDIO), Gradle Wrapper(`gradlew.bat`)
- SQL 분석: JSqlParser / 테스트: JUnit 5, AssertJ
- DB: 개발·테스트·데모는 파일형 DB(SQLite) + Sakila 샘플. MySQL·PostgreSQL은 JDBC 드라이버로 지원.

## 개발 환경 (이 PC)
- JDK 21 설치됨(`C:\Program Files\Java\jdk-21.0.1`), Node.js 있음.
- **Docker, MySQL 없음.** Testcontainers처럼 Docker가 필요한 방법은 로컬에서 쓰지 않는다.
  MySQL·PostgreSQL 통합 테스트는 GitHub Actions의 서비스 컨테이너에서 돌린다.
- 셸은 PowerShell. 빌드: `.\gradlew.bat build`, 테스트: `.\gradlew.bat test`.

## 설계 원칙
- **계산은 도구에, 판단은 Skill에.** 조인 경로 찾기, 행 수 제한, 마스킹처럼 답이 하나로 정해지는 일은 서버가 하고 테스트로 검증한다. "어떤 테이블부터 볼지", "결과를 어떻게 설명할지"는 Skill 문서에 둔다.
- **안전장치는 여러 겹으로.** SQL 검사 + 읽기 전용 연결 + 시간·행 수 제한 + 마스킹. 하나가 뚫려도 다음 겹이 막는다. README에는 "가장 확실한 방법은 읽기 전용 DB 계정"이라고 분명히 쓴다.
- **STDIO 주의:** MCP 통신이 표준출력(stdout)을 쓰므로 배너·로그를 stdout에 내보내지 않는다(로그는 stderr 또는 파일).

## 포트폴리오 연동
- 포트폴리오(`C:\Users\WoonHo\Desktop\98Woonho.github.io`)는 빌드 때 이 저장소의 `showcase/showcase.json`, `showcase/demos/*.json`을 가져간다.
- 포트폴리오의 `scripts/build-showcase.mjs`는 현재 npm 패키지만 실행할 수 있다. Java 서버 실행 방식을 추가해야 하며, 포트폴리오 쪽 수정도 사용자 확인 후 진행한다.
- 데모(`showcase/demos/*.json`)는 실제 실행 기록을 바탕으로 만들고, 캡션에 날짜·모델·버전을 적는다.
