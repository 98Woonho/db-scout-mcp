# 릴리스 방법

새 버전을 내는 순서입니다. 버전 올리기, 커밋, 태그, push는 **저장소 주인이 직접** 합니다(Claude는 명령만 제시합니다).

## 1. 버전 올리기

버전은 두 곳에 있고, 두 값이 같아야 합니다. 다르면 `PluginFilesTest`가 실패합니다.

| 파일 | 항목 |
|---|---|
| `gradle.properties` | `version=0.1.1` |
| `plugin/.claude-plugin/plugin.json` | `"version": "0.1.1"` |

플러그인 실행기(`plugin/bin/Launch.java`)는 `plugin.json`의 version과 같은 태그(`v0.1.1`)의 Release에서 jar를 받습니다. 그래서 두 값이 어긋나면 설치한 사용자가 엉뚱한 jar를 받거나 받지 못합니다.

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

1. 태그, `gradle.properties`, `plugin.json`의 버전이 모두 같은지 확인합니다. 다르면 멈춥니다.
2. 빌드하고 단위 테스트를 돌립니다(MySQL·PostgreSQL 통합 테스트는 `ci.yml`이 push마다 이미 돌립니다).
3. `db-scout-mcp.jar`와 해시 파일 `db-scout-mcp.jar.sha256`을 만들어 GitHub Release에 올립니다.

시크릿은 필요 없습니다(저장소 기본 토큰 `GITHUB_TOKEN`만 씀).

## 5. 확인할 것

- GitHub의 Actions 탭에서 Release 워크플로가 성공했는지, Releases에 jar와 `.sha256` 두 파일이 올라왔는지 봅니다.
- **저장소 밖의 폴더**에서 Claude Code로 플러그인을 설치(또는 업데이트)해 봅니다. 저장소 안에서는 로컬 빌드 jar를 쓰므로 내려받기 경로가 확인되지 않습니다.
  - 처음 실행할 때 `~/.db-scout/jars/<버전>/db-scout-mcp.jar`가 생기는지
  - `list_connections`가 동작하는지, 플러그인 설정에 DB URL을 넣었다면 그 DB로 접속되는지

## 잘못 올렸을 때

- 같은 태그를 다시 쓰지 말고 **버전을 하나 올려** 다시 릴리스합니다. 이미 받은 사용자는 `~/.db-scout/jars/<버전>/`에 그 버전 jar를 보관하고 있어서, 같은 버전 번호로 jar를 바꿔 올리면 사용자마다 다른 jar를 쓰게 됩니다.
- 문제가 있는 Release는 GitHub에서 삭제하거나 설명에 표시해 둡니다.
