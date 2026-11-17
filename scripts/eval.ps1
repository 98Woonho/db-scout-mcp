<#
.SYNOPSIS
  Skill 평가(claude plugin eval)를 실행한다. 실제 Claude가 돌아가므로 비용이 든다.

.DESCRIPTION
  - 실행 전에 서버 jar를 빌드한다. 저장소 안에서 실행되므로 플러그인 실행기가 로컬 빌드를 쓴다.
  - 기본값: 문제당 1회, 비교 실행 없음, 비용 상한 3달러. DB는 내장 Sakila(설정 불필요).
  - -NoSkills: Skill을 뺀 복사본(plugin-noskills/)으로 같은 문제를 푼다(Skill 효과 비교용).
  - 나머지 인자는 claude plugin eval 에 그대로 넘긴다. 예) -Case top-renters
  - Claude Code CLI 경로는 환경변수 CLAUDE_BIN 으로 바꿀 수 있다(기본: PATH 의 claude).

.EXAMPLE
  .\scripts\eval.ps1                       # 전체 7문제, 각 1회
  .\scripts\eval.ps1 --case store-revenue  # 한 문제만
  .\scripts\eval.ps1 -NoSkills             # Skill 없이
#>
param(
    [switch]$NoSkills,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

& "$root\gradlew.bat" bootJar -q
if ($LASTEXITCODE -ne 0) { throw '서버 빌드 실패' }

$target = './plugin'
if ($NoSkills) {
    $copy = Join-Path $root 'plugin-noskills'
    if (Test-Path $copy) { Remove-Item -Recurse -Force $copy }
    Copy-Item -Recurse (Join-Path $root 'plugin') $copy
    Remove-Item -Recurse -Force (Join-Path $copy 'skills')
    if (Test-Path (Join-Path $copy 'evals\results')) { Remove-Item -Recurse -Force (Join-Path $copy 'evals\results') }
    $target = './plugin-noskills'
}

$prefix = 'mcp__plugin_db-scout_db-scout__'
$tools = 'list_connections', 'list_tables', 'describe_table', 'search_schema', 'find_join_path',
         'sample_rows', 'profile_column', 'explain_query', 'run_query' | ForEach-Object { $prefix + $_ }

$claude = if ($env:CLAUDE_BIN) { $env:CLAUDE_BIN } else { 'claude' }
# --ablation none: '플러그인 전체 없음' 비교는 하지 않는다(Skill 효과는 -NoSkills 로 따로 비교)
# --no-publish: 보고서를 claude.ai 에 올리지 않고 로컬(plugin/evals/results/)에만 둔다
# --trust-plugin: 이 저장소에서 직접 만든 플러그인이므로 첫 실행 확인 창을 건너뛴다
$evalArgs = @('plugin', 'eval', $target, '--runs', '1', '--ablation', 'none', '--allow-real-servers',
              '--no-publish', '--trust-plugin', '--max-cost-usd', '3', '--allow-tools') + $tools + $Rest
Write-Host "> $claude plugin eval $target ... (도구 $($tools.Count)개 허용, 문제당 1회, 상한 `$3)"
& $claude @evalArgs
exit $LASTEXITCODE
