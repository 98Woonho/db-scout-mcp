# 릴리스 방법

새 버전을 내는 순서입니다. 버전 올리기, 커밋, 태그, push는 **저장소 주인이 직접** 합니다(Claude는 명령만 제시합니다).

배포되는 곳은 세 군데이고, 태그 push 한 번으로 모두 갱신됩니다.

| 배포처 | 내용 | 누가 쓰나 |
|---|---|---|
| GitHub Release | 서버 `db-scout-mcp.jar` + 해시 `.sha256` | 두 실행기가 여기서 jar를 받음 |
| npm `db-scout-mcp` | 실행기 `npm/launch.js`(jar는 들어 있지 않음) | `npx db-scout-mcp`로 연결하는 MCP 클라이언트 |
| MCP 공식 레지스트리 | `server.json`(npm 패키지를 가리킴) | 레지스트리에서 서버를 찾는 사람·도구 |

## 1. 버전 올리기

버전은 네 곳에 있고 모두 같아야 합니다. 다르면 `PluginFilesTest`가 실패합니다. 스크립트로 한 번에 맞춥니다.

```powershell
node scripts/set-version.mjs 0.1.1
```

| 파일 | 항목 |
|---|---|
| `gradle.properties` | `version=0.1.1` |
| `plugin/.claude-plugin/plugin.json` | `"version": "0.1.1"` |
| `npm/package.json` | `"version": "0.1.1"` |
| `server.json` | `"version"`과 `packages[0].version` |

플러그인 실행기(`plugin/bin/Launch.java`)와 npm 실행기(`npm/launch.js`)는 자기 버전과 같은 태그(`v0.1.1`)의 Release에서 jar를 받습니다. 그래서 값이 어긋나면 설치한 사용자가 엉뚱한 jar를 받거나 받지 못합니다.

버전 규칙(유의적 버전)
- `0.1.0 → 0.1.1`: 버그 수정, 문서·Skill 다듬기
- `0.1.0 → 0.2.0`: 도구·설정 추가, 동작 변경(예: 가리기 규칙 변경)

## 2. 확인

```powershell
.\gradlew.bat build    # 테스트 전부(버전 일치 검사 포함)
```

Skill이나 평가 문제를 바꿨다면 필요할 때 평가도 돌립니다(비용 발생, 약 $1). [README의 다시 평가하기](../README.md#다시-평가하기)를 보세요.

## 3. 커밋, 태그, push

```powershell
git add -A
git commit -m "버전 0.1.1"
git tag v0.1.1
git push origin main
git push origin v0.1.1
```

## 4. 자동으로 일어나는 일

태그 `v*`를 push하면 `.github/workflows/release.yml`이 돕니다.

1. 태그, `gradle.properties`, `plugin.json`의 버전이 모두 같은지 확인합니다. 다르면 멈춥니다(npm·server.json 버전은 다음 단계의 테스트가 확인).
2. 빌드하고 단위 테스트를 돌립니다(MySQL·PostgreSQL 통합 테스트는 `ci.yml`이 push마다 이미 돌립니다).
3. `db-scout-mcp.jar`와 해시 파일 `db-scout-mcp.jar.sha256`을 만들어 GitHub Release에 올립니다.
4. npm 실행기를 임시 폴더에 설치해, 방금 올린 Release에서 jar를 받아 서버가 실제로 뜨는지 확인합니다.
5. npm에 배포합니다(Trusted Publishing: GitHub Actions가 npm에 직접 로그인하는 방식이라 토큰이 필요 없음).
6. npm에 반영될 때까지 기다린 뒤 MCP 공식 레지스트리에 등록합니다(GitHub 로그인 방식, 시크릿 없음).

각 단계는 이미 했으면 건너뜁니다. 중간에 실패하면 원인을 고친 뒤 Actions에서 **Re-run**하면 이어서 진행됩니다.

## 5. 확인할 것

- GitHub의 Actions 탭에서 Release 워크플로가 성공했는지, Releases에 jar와 `.sha256` 두 파일이 올라왔는지 봅니다.
- npmjs.com의 `db-scout-mcp`에 새 버전이 보이는지, [MCP 레지스트리](https://registry.modelcontextprotocol.io)에서 `db-scout-mcp`를 검색해 새 버전이 보이는지 봅니다.
- **저장소 밖의 폴더**에서 Claude Code로 플러그인을 설치(또는 업데이트)해 봅니다. 저장소 안에서는 로컬 빌드 jar를 쓰므로 내려받기 경로가 확인되지 않습니다.
  - 처음 실행할 때 `~/.db-scout/jars/<버전>/db-scout-mcp.jar`가 생기는지
  - `list_connections`가 동작하는지, 플러그인 설정에 DB URL을 넣었다면 그 DB로 접속되는지

## 실패했을 때

- **npm 배포 단계 실패**: Trusted Publishing 설정(아래 최초 1회 설정)을 확인합니다. npmjs.com → Staged Packages에 올라가 있다면 승인한 뒤 Re-run합니다. Trusted Publisher의 Allowed actions에 `npm publish`가 있어야 자동으로 끝납니다.
- **"npm 조회에 나타나지 않았습니다"**: 대개 반영 지연입니다. 잠시 뒤 Re-run합니다.
- **MCP 레지스트리 단계만 실패**: 그냥 Re-run하면 됩니다. Release와 npm은 이미 있으므로 건너뜁니다.

## 잘못 올렸을 때

- 같은 태그를 다시 쓰지 말고 **버전을 하나 올려** 다시 릴리스합니다. 이미 받은 사용자는 `~/.db-scout/jars/<버전>/`에 그 버전 jar를 보관하고 있어서, 같은 버전 번호로 jar를 바꿔 올리면 사용자마다 다른 jar를 쓰게 됩니다. 워크플로도 이미 있는 Release는 덮어쓰지 않습니다.
- 문제가 있는 Release는 GitHub에서 삭제하거나 설명에 표시해 둡니다. npm은 같은 버전 번호를 다시 쓸 수 없습니다.

## 최초 1회 설정 (npm)

Trusted Publishing은 npm 패키지의 설정 화면에서 켭니다. 그래서 **첫 배포는 직접** 해서 패키지를 먼저 만듭니다.
지금 `npm/package.json`이 0.1.0이고 Release v0.1.0이 이미 있으므로, 0.1.0을 그대로 올리면 바로 동작합니다.

```powershell
npm login                       # npmjs.com 계정(2단계 인증)
cd npm
Copy-Item ..\LICENSE LICENSE
npm publish                     # db-scout-mcp@0.1.0
Remove-Item LICENSE
cd ..
```

- 새 패키지는 npm 자동 검토 동안 `0.0.0-stage`라는 자리 표시 버전이 먼저 보이고, 1분쯤 뒤 실제 버전이 공개됩니다. Staged Packages에 아무것도 없으면 기다리면 됩니다.

그다음 npmjs.com에서:
- [ ] 패키지 **Settings → Trusted publishing → GitHub Actions**
  - Organization or user: `98Woonho`
  - Repository: `db-scout-mcp`
  - Workflow filename: `release.yml`
  - Environment: 비워 둠
  - Allowed actions: **`npm publish` 허용**(stage만 허용하면 웹 승인이 필요해 자동 배포가 멈춤)
- [ ] 첫 자동 배포 성공 후 **Settings → Publishing access → "Require two-factor authentication and disallow tokens"**(토큰 배포 차단, Trusted Publishing은 계속 동작)

MCP 레지스트리는 따로 설정할 것이 없습니다. GitHub 로그인으로 `io.github.98Woonho/*` 이름을 쓸 권한이 확인됩니다.
