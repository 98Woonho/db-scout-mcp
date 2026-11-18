# 이어서 할 작업

다른 PC에서 개발을 이어가기 위한 정리입니다. 작업 규칙과 결정 사항은 [CLAUDE.md](../CLAUDE.md)에 있습니다. 이 문서는 **지금 어디까지 왔고 무엇이 남았는지**만 다룹니다.

마지막 정리: 2026-11-18

---

## 1. 지금까지 한 것

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | Gradle 뼈대, Sakila 데모 DB(빌드 때 받아 해시 확인) | 완료 |
| 2 | SQL 검사기(차단 사례 94개) | 완료 |
| 3 | 읽기 전용 연결, 행 수·시간 제한, 개인정보 가리기 | 완료 |
| 4 | MCP 도구 9개, 숨길 테이블 설정 | 완료 |
| 5 | CI: MySQL 8.4·PostgreSQL 16 통합 테스트 | 완료, GitHub Actions 통과 확인 |
| 6 | 플러그인(실행기 Launch.java), Skill 2개, 평가 7문제, release.yml | 완료. 평가: Skill 있음 30/30, 없음 26/30 |
| 6-1 | 평가에서 찾은 개선(A-1 Skill·채점, A-2 가리기 규칙) | 완료(2026-11-18). A-1 재평가는 아직 |
| 7 | README, 릴리스 안내 문서(docs/RELEASING.md) | 완료(2026-11-18) |
| 8 | 포트폴리오 연동(showcase) | **남음** |

테스트는 `.\gradlew.bat build` 한 번으로 전부 돌아갑니다. MySQL·PostgreSQL 통합 테스트는 접속 정보 환경변수가 없으면 건너뜁니다. 그래서 로컬에서는 전체 465개 중 430개가 통과하고 35개를 건너뛰는 것이 정상입니다.

**두 번째 PC(`C:\Users\leeunho`) 주의:** PATH와 JAVA_HOME이 Java 1.8을 가리킵니다. JDK 21은 `C:\Users\leeunho\.jdks\temurin-21.0.10`에 있습니다. 빌드할 때는 그 셸에서만 바꿔 씁니다.
```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21.0.10"; .\gradlew.bat build
```
플러그인은 PATH의 `java`로 `Launch.java`를 실행하므로, 이 PC에서 C단계(설치 확인)를 하려면 PATH의 java가 21 이상이어야 합니다(시스템 설정 변경은 사용자가 직접).

---

## 2. 새 PC 준비

1. **JDK 21** 설치. `java -version`으로 21인지 확인합니다.
2. 저장소 받기
   ```powershell
   git clone https://github.com/98Woonho/db-scout-mcp.git
   cd db-scout-mcp
   .\gradlew.bat build
   ```
   첫 빌드 때 Gradle(약 140MB)과 Sakila SQL(약 8.5MB)을 내려받습니다. Gradle을 따로 설치할 필요는 없습니다.
3. **Claude Code**에서 이 폴더를 엽니다. CLAUDE.md는 자동으로 읽힙니다.
4. 평가를 돌리려면 `claude` CLI 경로를 확인합니다. 이전 PC에서는 PATH에 없고 VS Code 확장 안에 있었습니다.
   ```powershell
   Get-ChildItem "$env:USERPROFILE\.vscode\extensions\anthropic.claude-code-*\resources\native-binary\claude.exe"
   $env:CLAUDE_BIN = "<위에서 찾은 경로>"
   ```
5. 8단계를 하려면 저장소 두 개가 더 필요합니다. 이전 PC에서는 이 저장소와 같은 폴더에 있었습니다.
   - 포트폴리오: `98Woonho/98Woonho.github.io`
   - 참고용 이전 프로젝트: `98Woonho/korea-transit-mcp`

**다른 PC로 넘어오지 않는 것**
- 평가 결과(`plugin/evals/results/`)는 git에서 제외돼 있습니다. 실행 기록(trace)도 이전 PC의 임시 폴더에 있었습니다. 결과 숫자는 CLAUDE.md에 적어 두었습니다. 데모용 실행 기록이 필요하면 평가를 다시 돌려야 합니다(아래 8단계 참고).
- 로컬 MySQL·PostgreSQL 확인용 압축 파일은 확인 후 지웠습니다. 다시 필요하면 CLAUDE.md "개발 환경"의 방법대로 받습니다(사용자 승인 후).

---

## 3. 남은 작업 (권장 순서)

### A. 평가에서 찾은 개선 2가지 — 완료(2026-11-18), A-1 재평가만 남음

평가 점수는 만점이었지만, 실행 기록을 사람이 대조하다가 찾은 문제입니다.

**한 것**
- A-1: `data-question` Skill 1단계에 "계산하지 않은 기준에 대해 단정하지 않는다" 추가, 채점 `store-revenue/graders/04-no-unverified-claim.md` 추가(채점 항목 30 → 31개). 사용자 결정으로 **재평가는 나중에** 합니다(D단계 데모 기록이 필요할 때 함께).
- A-2: `LineageAnalyzer`가 참/거짓 조건과 CASE의 WHEN을 값으로 보지 않음. 추가로, 결과 이름(별칭)이 email로 끝나면 가리는 규칙도 "값 자리에 컬럼이 없고 개인정보를 조건·COUNT에만 쓴 컬럼"(`SUM(email IS NOT NULL) AS has_email`)에는 적용하지 않음(사용자 승인). `MaskingTest`에 14개 추가. CLAUDE.md 결정 사항 갱신.
- 아직 v0.1.0을 릴리스하지 않았으므로 이 변경은 v0.1.0에 그대로 포함합니다.

아래는 원래 계획입니다(기록용).

**A-1. Skill 보강: 확인하지 않은 기준에 대해 단정하지 않기** (평가 재실행 약 $0.5)
- 문제: 매장 매출 문제에서 기준 두 가지만 계산하고 "다른 기준으로 봐도 2번 매장이 1위"라고 썼습니다. 고객 등록 매장 기준이면 1번 매장이 1위입니다. 결과에 없는 숫자("결제 5,016건")도 섞였습니다. Skill이 없어도 같은 실수를 했습니다.
- 할 일
  - `plugin/skills/data-question/SKILL.md` 1단계 "질문을 나눈다"에 추가: 계산하지 않은 기준에 대해 "어느 기준이든 같다"고 말하지 않는다. 기준 후보가 두세 개면 모두 계산해 비교한다.
  - `plugin/evals/data-question/store-revenue/graders/`에 AI 채점 추가: "모든 기준에서 2번 매장이 1위"라고 단정하면 불합격(고객 등록 매장 기준은 1번이 1위).
  - `.\scripts\eval.ps1 --case store-revenue`로 이 문제만 실행해 확인합니다(비용 확인 후). 스크립트가 `--runs 1`을 넘기므로, 3회로 하려면 `scripts/eval.ps1`의 `'--runs', '1'`을 잠시 바꿉니다.

**A-2. 서버 가리기 규칙 다듬기: 참/거짓 조건은 가리지 않기** (무료, 테스트만)
- 문제: `SUM(email IS NOT NULL)`("이메일이 있는 고객 수")가 `5***`로 가려졌습니다. 참/거짓을 센 개수인데 이메일 값으로 취급된 것입니다.
- 방향: 참/거짓만 내는 조건(`=`, `<>`, `LIKE`, `IS NULL`, `IN`, `BETWEEN`, `EXISTS` 등)은 WHERE와 똑같이 보고 가리지 않습니다. CASE는 결과로 나가는 값(THEN/ELSE)만 봅니다. 기존 결정("WHERE 조건은 막지 않는다")과 맞추는 변경입니다.
- 할 일
  - `src/main/java/.../mask/LineageAnalyzer.java`의 `KindCollector`에서 비교·LIKE·IS NULL·IN·BETWEEN·EXISTS 식은 안으로 들어가지 않게 하고, `CaseExpression`은 THEN/ELSE만 방문하도록 재정의합니다.
  - 그대로 가려져야 하는 것: `UPPER(email)`, `MAX(email)`, `CASE … THEN email END`. 이 경우를 지키는 테스트가 `MaskingTest`에 있습니다.
  - 새 테스트: `SUM(email IS NOT NULL)`, `COUNT(CASE WHEN email LIKE '%.org' THEN 1 END)`, `CASE WHEN email LIKE '%gmail%' THEN 'gmail' ELSE 'other' END`는 가리지 않는다.
  - CLAUDE.md "결정 사항"의 "CASE 안에서 쓰여도 가림" 문장을 고칩니다.
  - 개인정보 처리 규칙이 바뀌는 것이므로 진행 전에 사용자 확인을 받습니다.

### B. 7단계: README와 릴리스 안내 — 완료(2026-11-18)

README.md(소개, 작동 방식, 도구·Skill, 안전장치와 한계, 품질 검증, 설치·설정, 구조)와 docs/RELEASING.md를 썼습니다. 소개 페이지 링크는 D단계 후에 README 위쪽에 추가합니다. 아래는 원래 계획입니다.

**README.md** (지금은 짧은 소개만 있음). 이전 프로젝트 README의 구성을 따릅니다.
- 소개, 작동 방식(서버는 LLM을 부르지 않음), 소개 페이지 링크(8단계 후)
- 설치
  - Claude Code 플러그인: `/plugin marketplace add 98Woonho/db-scout-mcp` → `/plugin install db-scout@db-scout-mcp`
  - 그 밖의 MCP 클라이언트: `java -jar db-scout-mcp.jar`와 환경변수 `DB_SCOUT_URL` 등
- 설정: 접속(환경변수, 여러 접속은 설정 파일), `mask-columns`, `unmask-columns`, `hidden-tables`, 제한값
- 안전장치 네 겹과 **한계**
  - 이름 기반 판정이라 자유 입력 칸 안의 개인정보는 못 찾음
  - 숨긴 테이블도 시스템 카탈로그로 구조는 보임
  - 가장 확실한 방법은 읽기 전용 DB 계정
- 도구 9개 표, Skill 2개 설명
- 품질 검증: 테스트 종류와 개수, CI(3개 DB), 평가 결과 표(30/30 대 26/30)와 알게 된 것, AI 채점을 사람이 대조한 과정과 한계(Sakila는 유명한 DB라 Skill 없이도 구조를 앎). 숫자는 CLAUDE.md "알아 둘 사실" 참고.
- CI 배지, 라이선스(MIT, Sakila는 THIRD_PARTY_NOTICES.md)

**docs/RELEASING.md**
- 버전 올리기: `gradle.properties`의 `version`과 `plugin/.claude-plugin/plugin.json`의 `version`을 같이 바꿉니다. 다르면 `PluginFilesTest`가 실패합니다.
- 커밋 후 태그 `v<버전>`을 push하면 `release.yml`이 jar와 `.sha256`을 GitHub Release에 올립니다.
- 버전 올리기와 커밋·태그·push는 사용자가 직접 합니다. Claude는 PowerShell 명령만 제시합니다.

### C. 첫 릴리스 v0.1.0과 실제 설치 확인

아직 한 번도 확인하지 못한 경로입니다.
1. 사용자가 태그 `v0.1.0`을 push → Actions의 Release 워크플로 성공 확인
2. 다른 폴더(저장소 밖)에서 Claude Code로 플러그인을 설치합니다. 이때 `Launch.java`가 Release에서 jar를 받아 SHA-256을 확인하고 `~/.db-scout/jars/0.1.0/`에 보관하는지 확인합니다. 저장소 안에서는 로컬 빌드를 쓰므로 이 경로가 확인되지 않습니다.
3. 플러그인 설정(DB URL 등)을 넣었을 때 그 DB로 접속되는지 확인합니다.
4. 문제가 있으면 고친 뒤 v0.1.1로 다시 릴리스합니다.

### D. 8단계: 포트폴리오 연동 (B안으로 결정됨)

포트폴리오가 서버를 실행하지 않고, 이 저장소의 도구 목록 파일을 읽는 방식입니다.

**이 저장소에서 할 일**
- `showcase/showcase.json`: korea-transit-mcp의 `showcase/showcase.json` 형식을 따릅니다. `npm` 대신 `tools` 항목을 둡니다. 내용은 소개, 하이라이트(평가 30/30 대 26/30, 차단 사례 94개, 3개 DB CI, LLM 비용 0원), principles, skills, toolGroups, quality, install.
- `showcase/tools.json`: 실제 서버의 도구 목록(이름, 제목, 설명). `McpStdioTest`에 "jar를 띄워 받은 도구 목록이 이 파일과 같은가"를 추가해, 서로 어긋나면 CI가 실패하게 합니다. 처음 만들 때는 테스트가 파일을 쓰는 모드를 둡니다.
- `showcase/demos/*.json`: **실제 실행 기록을 바탕으로** 만듭니다. 이전 PC의 평가 기록은 넘어오지 않으므로 평가를 다시 돌리거나(비용 확인 후, `--keep-temp`라 기록이 남음) Claude Code에서 직접 질문해 기록합니다. 후보는 다음과 같습니다.
  - 카테고리 매출(조인 경로 확인 → SQL → 답)
  - 개인정보 요청(가려진 채 안내)
  - 쓰기 요청(실행하지 않고 영향 행 수와 SQL 안내)
  - 매장 매출(기준에 따라 답이 바뀜)

  캡션에는 날짜, 모델, 버전을 적습니다.

**포트폴리오 저장소에서 할 일** (사용자 확인 후)
- `scripts/build-showcase.mjs`의 `listTools()`: `showcase.json`에 `tools`가 있으면 서버를 실행하지 않고 `showcase/tools.json`을 읽습니다(로컬 또는 GitHub raw). korea-transit은 지금처럼 npm으로 실행합니다.
- `showcase.config.json`에 `{ "repo": "98Woonho/db-scout-mcp", "ref": "main", "local": "../db-scout-mcp" }` 추가
- `node scripts/build-showcase.mjs --local`로 확인합니다.

### E. 나중에 검토할 것
- MCP 공식 레지스트리 등록: npm·PyPI 패키지가 아니라 jar라서 등록 방식(MCPB 등)을 따로 조사해야 합니다.
- 평가를 문제당 3회로 늘려 결과의 흔들림 보기(비용 약 3배).
- Sakila처럼 유명하지 않은 DB로 평가: Claude가 구조를 미리 모르는 상황에서 Skill 효과를 보려면 필요합니다.

---

## 4. 새 대화를 시작할 때

Claude Code에 이렇게 말하면 이어집니다.

> docs/NEXT_STEPS.md 읽고 이어서 하자. C(첫 릴리스 v0.1.0 확인)부터.
