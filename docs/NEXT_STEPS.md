# 이어서 할 작업

개발을 이어가기 위한 정리입니다. 작업 규칙과 결정 사항은 [CLAUDE.md](../CLAUDE.md)에 있습니다. 이 문서는 **지금 어디까지 왔고 무엇이 남았는지**만 다룹니다.

마지막 정리: 2026-11-22

---

## 1. 지금 상태

| 단계 | 내용 | 상태 |
|---|---|---|
| 1~5 | 뼈대, SQL 검사기(차단 사례 94개), 읽기 전용·제한·가리기, 도구 9개, CI(MySQL 8.4·PostgreSQL 16) | 완료 |
| 6 | 플러그인, Skill 2개, 평가 7문제(Skill 있음 30/30, 없음 26/30) | 완료 |
| 6-1 | 평가에서 찾은 개선: 가리기 규칙(참/거짓 조건은 가리지 않음), Skill에 "계산하지 않은 기준은 단정하지 않기", 채점 항목 추가(30 → 31개) | 완료. **store-revenue 재평가는 아직** |
| 7 | README, docs/RELEASING.md | 완료 |
| C | 첫 릴리스 v0.1.0과 실제 설치 확인 | 완료(2026-11-22). 플러그인 화면에서 설치 → Release jar 내려받기(해시 일치) → 질문 6개 정상 |
| 8 | 포트폴리오 연동(showcase) | 남음 |

- 마지막 커밋 `70b9512`까지 push 완료. 이 커밋의 GitHub Actions CI(3개 DB) 통과 확인(2026-11-18).
- 테스트: 전체 465개. 로컬에서는 430개 통과, MySQL·PostgreSQL 통합 테스트 35개는 건너뛰는 것이 정상입니다.
- 릴리스 v0.1.0: 태그는 커밋 `70b9512`에 있고, GitHub Release(2026-11-18)에 `db-scout-mcp.jar`와 `.sha256`이 올라가 있습니다. 그 뒤 커밋은 문서 수정뿐이라 새 릴리스는 필요 없습니다.

---

## 2. 다음 작업 (권장 순서)

### C. 첫 릴리스 v0.1.0과 설치 확인 — ✅ 완료(2026-11-22)

**C-3 결과**: Claude Code의 플러그인 관리 화면에서 검색해 설치. MCP 서버 연결, Skill 2개 표시, `~/.db-scout/jars/0.1.0/db-scout-mcp.jar`를 Release에서 받음(해시 `89de3238…` 일치). 질문 6개(DB 설명, 카테고리 매출, 최다 대여 고객, 이메일 요청, 대여료 변경 요청, 매장 매출)에 정상 답변.

**C-1. 태그 push** — ✅ 완료(2026-11-18). Release에 jar와 `.sha256` 두 파일이 있습니다.

**C-2. 내려받기 경로 확인** — ✅ 완료(2026-11-22)

2026-11-18에 Claude Code 설치 없이 실행기(`plugin/bin/Launch.java`)를 저장소 밖에서 확인했습니다. 방법은 이렇습니다.
- `plugin/`을 저장소 밖 임시 폴더에 복사하고, `java -Duser.home=<임시 홈> Launch.java <복사한 폴더>`로 실행합니다. 홈을 바꾸면 실제 `~/.db-scout`를 건드리지 않습니다.
- 표준입력으로 MCP 메시지(`initialize` → `notifications/initialized` → `tools/call list_connections`)를 보내 응답을 봅니다.

| 확인 | 결과 |
|---|---|
| 저장소 밖에서는 로컬 빌드를 쓰지 않음 | ✅ |
| 받아 둔 jar(`<홈>/.db-scout/jars/0.1.0/`)로 서버 실행 | ✅ |
| 설정 없음(치환 안 된 `${user_config.db_url}` 포함) → 내장 Sakila | ✅ |
| `DB_SCOUT_PLUGIN_URL`에 다른 SQLite 지정 → 그 DB(`main`, demo:false)로 접속, 응답에 URL 안 나옴 | ✅ |
| 빈 홈에서 Release의 jar를 받아 SHA-256 확인 후 보관(첫 실행 약 14초, 해시가 Release의 `.sha256`과 일치, 임시 파일 안 남음) | ✅ 2026-11-22 |
| 두 번째 실행은 다시 받지 않음(약 4초) | ✅ 2026-11-22 |
| 없는 버전(9.9.9)은 404와 "인터넷 연결이나 플러그인 버전을 확인하세요"로 멈추고 파일을 남기지 않음 | ✅ 2026-11-22 |

확인 스크립트는 임시 폴더에 있어서 남지 않습니다. 다시 만들 때 주의할 점:
- Windows PowerShell 5.1은 BOM 없는 `.ps1`의 한글을 깨뜨립니다. 스크립트는 영어(ASCII)로만 씁니다.
- 자식 프로세스 표준입력 앞에 BOM이 붙으면 서버가 첫 메시지를 읽지 못합니다. 실행 전에 `[Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)`를 둡니다.

**C-3. 실제 Claude Code 설치 확인** (사용자가 직접. Claude 설정이 바뀌는 일이라)
1. `java -version`이 21 이상인지 봅니다. 플러그인은 PATH의 `java`로 실행됩니다.
2. **저장소 밖 폴더**에서 Claude Code를 열고 설치합니다.
   ```
   /plugin marketplace add 98Woonho/db-scout-mcp
   /plugin install db-scout@db-scout-mcp
   ```
3. 설정을 비운 채로 "이 DB에 뭐가 들어 있어?"라고 물어 내장 Sakila로 답하는지, `~/.db-scout/jars/0.1.0/db-scout-mcp.jar`가 생겼는지 봅니다.
4. 플러그인 설정에 DB URL을 넣으면 그 DB로 접속되는지 봅니다(SQLite 파일 하나로 충분: `jdbc:sqlite:C:/경로/파일.db`).
5. 문제가 있으면 고친 뒤 **v0.1.1로** 다시 릴리스합니다(같은 버전 번호로 jar를 바꿔 올리지 않음. docs/RELEASING.md 참고).

### A-1 재평가: store-revenue (비용 약 $0.2~0.5, 사용자 확인 후)
- Skill과 채점 항목(`plugin/evals/data-question/store-revenue/graders/04-no-unverified-claim.md`)은 고쳤지만 실제로 돌려 보지는 않았습니다.
- `.\scripts\eval.ps1 --case store-revenue` (Skill 없이 비교하려면 `-NoSkills`도). 실행 전에 무료 검사 `.\gradlew.bat test --tests "*PluginFilesTest*"`.
- D단계 데모용 실행 기록이 필요할 때 함께 돌리면 비용을 아낄 수 있습니다(`--keep-temp`라 기록이 남음).
- 결과가 나오면 README "품질 검증"과 CLAUDE.md "알아 둘 사실"을 갱신합니다.

### D. 포트폴리오 연동 (B안으로 결정됨) ← 여기부터

2026-11-22 진행 상황
- ✅ `showcase/showcase.json`(소개 자료, 데모는 비어 있음), `showcase/tools.json`(McpStdioTest가 실제 서버와 비교)
- ✅ 포트폴리오: `build-showcase.mjs`가 `showcase.tools`가 있으면 파일을 읽음, 첫 문장 자르기에서 "..." 제외, 상세 페이지의 인증키 단계·데모 절을 있을 때만 표시, `showcase.config.json`에 추가. `--local` 빌드와 `npm run build` 통과
- ⏳ **데모(`showcase/demos/*.json`)**: 실제 실행 기록 필요. 아래 계획 참고

포트폴리오가 서버를 실행하지 않고, 이 저장소의 도구 목록 파일을 읽는 방식입니다.

**이 저장소에서 할 일**
- `showcase/showcase.json`: korea-transit-mcp의 `showcase/showcase.json` 형식을 따릅니다. `npm` 대신 `tools` 항목을 둡니다. 내용은 소개, 하이라이트(평가 30/30 대 26/30, 차단 사례 94개, 3개 DB CI, LLM 비용 0원), principles, skills, toolGroups, quality, install.
- `showcase/tools.json`: 실제 서버의 도구 목록(이름, 제목, 설명). `McpStdioTest`에 "jar를 띄워 받은 도구 목록이 이 파일과 같은가"를 추가해, 서로 어긋나면 CI가 실패하게 합니다. 처음 만들 때는 테스트가 파일을 쓰는 모드를 둡니다.
- `showcase/demos/*.json`: **실제 실행 기록을 바탕으로** 만듭니다. 이전 평가 기록은 남아 있지 않으므로 평가를 다시 돌리거나(비용 확인 후) Claude Code에서 직접 질문해 기록합니다. 후보:
  - 카테고리 매출(조인 경로 확인 → SQL → 답)
  - 개인정보 요청(가려진 채 안내)
  - 쓰기 요청(실행하지 않고 영향 행 수와 SQL 안내)
  - 매장 매출(기준에 따라 답이 바뀜)

  캡션에는 날짜, 모델, 버전을 적습니다.
- 소개 페이지가 생기면 README 위쪽에 링크를 추가합니다.

**포트폴리오 저장소에서 할 일** (사용자 확인 후)
- `scripts/build-showcase.mjs`의 `listTools()`: `showcase.json`에 `tools`가 있으면 서버를 실행하지 않고 `showcase/tools.json`을 읽습니다(로컬 또는 GitHub raw). korea-transit은 지금처럼 npm으로 실행합니다.
- `showcase.config.json`에 `{ "repo": "98Woonho/db-scout-mcp", "ref": "main", "local": "../db-scout-mcp" }` 추가
- `node scripts/build-showcase.mjs --local`로 확인합니다.

### E. 나중에 검토할 것
- MCP 공식 레지스트리 등록: npm·PyPI 패키지가 아니라 jar라서 등록 방식(MCPB 등)을 따로 조사해야 합니다.
- 평가를 문제당 3회로 늘려 결과의 흔들림 보기(비용 약 3배).
- Sakila처럼 유명하지 않은 DB로 평가: Claude가 구조를 미리 모르는 상황에서 Skill 효과를 보려면 필요합니다.

---

## 3. 새 대화를 시작할 때

Claude Code에 이렇게 말하면 이어집니다.

> docs/NEXT_STEPS.md 읽고 이어서 하자. D(포트폴리오 연동)부터.
