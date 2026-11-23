<#
.SYNOPSIS
  Reproducible Skill evaluation. Real runs incur Claude usage charges.
.DESCRIPTION
  -DryRun prints the plan without builds, file writes or Claude calls.
  Real runs preserve the plugin snapshot, input hashes and native results.
  MaxCostUsd is a per-invocation stop threshold: a running case can exceed it.
.EXAMPLE
  .\scripts\eval.ps1 -Suite holdout -Runs 1 -DryRun
  .\scripts\eval.ps1 -Suite holdout -Runs 1 -MaxCostUsd 1.5
  .\scripts\eval.ps1 -Suite holdout -Runs 1 -MaxCostUsd 1.5 -NoSkills
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$NoSkills,
    [ValidateSet('regression', 'holdout')][string]$Suite = 'regression',
    [ValidateRange(1, 10)][int]$Runs = 1,
    [string]$Case = '*',
    [ValidateRange(0.05, 100)][decimal]$MaxCostUsd = 3,
    [string]$Model = 'opus',
    [string]$JudgeModel = 'haiku',
    [switch]$DryRun,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if ($Rest.Count -gt 0) {
    if ($Rest.Count -ne 2 -or $Rest[0] -ne '--case' -or $Case -ne '*') {
        throw 'Use -Suite, -Case, -Runs, -Model, -JudgeModel and -MaxCostUsd.'
    }
    $Case = $Rest[1]
}
$evalDir = if ($Suite -eq 'holdout') { 'evals-holdout' } else { 'evals' }
$cases = @(Get-ChildItem -LiteralPath (Join-Path $root "plugin/$evalDir") -Filter case.yaml -File -Recurse |
    Where-Object { $_.FullName -notmatch '[\\/]results[\\/]' -and $_.Directory.Name -like $Case } | Sort-Object FullName)
if ($cases.Count -eq 0) { throw "No cases match '$Case' in '$Suite'." }
$graderCount = 0
foreach ($item in $cases) {
    $graderCount += @(Get-ChildItem -LiteralPath (Join-Path $item.Directory.FullName 'graders') -Filter '*.md' -File).Count
}
$condition = if ($NoSkills) { 'without-skills' } else { 'with-skills' }
$plan = [ordered]@{
    suite = $Suite; condition = $condition; cases = @($cases | ForEach-Object { $_.Directory.Name })
    caseCount = $cases.Count; gradersPerRun = $graderCount; runsPerCase = $Runs
    totalCaseRuns = $cases.Count * $Runs; model = $Model; judgeModel = $JudgeModel
    costStopThresholdUsd = $MaxCostUsd; data = 'Bundled Sakila SQLite only'
    ablation = 'none'; concurrency = 1; publish = $false
}
if ($DryRun) { $plan | ConvertTo-Json -Depth 8; return }
$claude = if ($env:CLAUDE_BIN) { $env:CLAUDE_BIN } else { 'claude' }
Get-Command $claude -ErrorAction Stop | Out-Null
Push-Location $root
$originalPath = $env:PATH
$savedEnvironment = @{}
try {
    if ($env:JAVA_HOME) { $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $originalPath }
    $configNames = @('SPRING_APPLICATION_JSON', 'SPRING_CONFIG_LOCATION', 'SPRING_CONFIG_ADDITIONAL_LOCATION',
        'SPRING_CONFIG_IMPORT', 'SPRING_PROFILES_ACTIVE', 'SPRING_PROFILES_INCLUDE')
    foreach ($entry in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'DB_SCOUT_*' -or $_.Name -in $configNames })) {
        $savedEnvironment[$entry.Name] = $entry.Value
        Remove-Item -LiteralPath ("Env:" + $entry.Name)
    }
    & "$root/gradlew.bat" test --tests '*PluginFilesTest*' --tests '*EvaluationReferenceTest*' bootJar -q
    if ($LASTEXITCODE -ne 0) { throw 'Free validation or build failed; Claude was not started.' }
    $runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $output = Join-Path $root "build/evaluations/$runId-$condition"
    # Launch.java uses the local jar only when the snapshot's parent is the repo.
    $snapshot = Join-Path $root "plugin-eval-$runId-$condition"
    New-Item -ItemType Directory -Path $output, $snapshot | Out-Null
    foreach ($name in @('.claude-plugin', '.mcp.json', 'bin', $evalDir)) {
        Copy-Item -LiteralPath (Join-Path $root "plugin/$name") -Destination $snapshot -Recurse
    }
    if (-not $NoSkills) { Copy-Item -LiteralPath (Join-Path $root 'plugin/skills') -Destination $snapshot -Recurse }
    # Pin the MCP process to the tested jar, even if the CLI copies the plugin.
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $mcpConfig = @{ mcpServers = @{ 'db-scout' = @{
        command = (Get-Command java.exe -ErrorAction Stop).Source
        args = @('-jar', (Join-Path $root 'build/libs/db-scout-mcp.jar'))
        env = @{ DB_SCOUT_URL = '' }
    } } }
    $snapshotMcp = Join-Path $snapshot '.mcp.json'
    [IO.File]::WriteAllText($snapshotMcp, ($mcpConfig | ConvertTo-Json -Depth 8), $utf8)
    $hashes = [ordered]@{}
    $inputs = @('scripts/eval.ps1', 'gradle.properties', 'docs/evaluation/holdout-v1.json')
    $inputs += @(Get-ChildItem -LiteralPath (Join-Path $root 'plugin') -File -Recurse |
        Where-Object { $_.FullName -notmatch '[\\/]results[\\/]' } |
        ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\', '/') })
    foreach ($relative in @($inputs | Sort-Object -Unique)) {
        $hashes[$relative] = (Get-FileHash -LiteralPath (Join-Path $root $relative) -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $ErrorActionPreference = 'Continue'
    $cliVersion = (& $claude --version 2>$null | Out-String).Trim()
    $versionExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($versionExit -ne 0) { throw 'Cannot read Claude CLI version.' }
    $manifest = [ordered]@{
        schemaVersion = 1; runId = $runId; startedAtUtc = [DateTime]::UtcNow.ToString('o'); status = 'running'
        plan = $plan; sourceCommit = (& git rev-parse HEAD | Out-String).Trim()
        dirtyWorkingTree = [bool](& git status --porcelain --untracked-files=all)
        inputSha256 = $hashes; cliVersion = $cliVersion
        jarSha256 = (Get-FileHash -LiteralPath "$root/build/libs/db-scout-mcp.jar" -Algorithm SHA256).Hash.ToLowerInvariant()
        evaluatedMcpConfigSha256 = (Get-FileHash -LiteralPath $snapshotMcp -Algorithm SHA256).Hash.ToLowerInvariant()
        pluginSnapshot = $snapshot; nativeResult = 'aggregate-result.json'
        actualModel = $null; toolCalls = $null; tokens = $null
        measurementNote = 'Unknown metrics stay null. Inspect native results and traces; do not infer zero.'
    }
    $manifestPath = Join-Path $output 'run-manifest.json'
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 12), $utf8)
    $prefix = 'mcp__plugin_db-scout_db-scout__'
    $tools = @('list_connections', 'list_tables', 'describe_table', 'search_schema', 'find_join_path',
        'sample_rows', 'profile_column', 'explain_query', 'run_query') | ForEach-Object { $prefix + $_ }
    $budget = $MaxCostUsd.ToString([Globalization.CultureInfo]::InvariantCulture)
    $evalArgs = @('plugin', 'eval', $snapshot, '--eval-dir', $evalDir, '--case', $Case,
        '--runs', "$Runs", '--model', $Model, '--judge-model', $JudgeModel,
        '--ablation', 'none', '--concurrency', '1', '--allow-real-servers',
        '--no-publish', '--trust-plugin', '--keep-temp', '--max-cost-usd', $budget,
        '--output-dir', $output, '--allow-tools') + $tools
    Write-Host "Evaluating $($cases.Count) cases x $Runs, $condition. Stop threshold USD $budget; a running case may exceed it."
    Write-Host "Artifacts: $output"
    $ErrorActionPreference = 'Continue'
    try {
        & $claude @evalArgs
        $evalExit = $LASTEXITCODE
    } catch {
        $manifest.status = 'launch-error'
        $manifest['finishedAtUtc'] = [DateTime]::UtcNow.ToString('o')
        [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 12), $utf8)
        throw
    }
    $ErrorActionPreference = 'Stop'
    $manifest.status = if ($evalExit -eq 0) { 'completed' } else { 'failed-or-partial' }
    $manifest['exitCode'] = $evalExit
    $manifest['finishedAtUtc'] = [DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 12), $utf8)
    if ($evalExit -ne 0) { throw "Evaluation exited $evalExit. Preserve partial artifacts at $output." }
} finally {
    foreach ($name in $savedEnvironment.Keys) { Set-Item -LiteralPath ("Env:" + $name) -Value $savedEnvironment[$name] }
    $env:PATH = $originalPath
    Pop-Location
}
