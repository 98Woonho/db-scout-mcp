// 버전을 네 곳에 한 번에 맞춘다. 사용법: node scripts/set-version.mjs 0.1.1
//  - gradle.properties            서버 jar 버전(기준)
//  - plugin/.claude-plugin/plugin.json  플러그인 실행기가 이 버전의 Release jar 를 받는다
//  - npm/package.json             npm 실행기가 이 버전의 Release jar 를 받는다
//  - server.json                  MCP 레지스트리(version, packages[].version)
// 네 값이 어긋나면 PluginFilesTest 가 실패한다. 커밋·태그는 직접 한다(docs/RELEASING.md).
import { readFileSync, writeFileSync } from "node:fs";

const version = process.argv[2];
if (!/^\d+\.\d+\.\d+$/.test(version ?? "")) {
  console.error("사용법: node scripts/set-version.mjs <버전>   예) node scripts/set-version.mjs 0.1.1");
  process.exit(1);
}

function edit(path, change) {
  const before = readFileSync(path, "utf8");
  const after = change(before);
  if (after === before) {
    console.log(`  ${path}: 바뀐 것 없음`);
    return;
  }
  writeFileSync(path, after);
  console.log(`  ${path}: ${version}`);
}

const jsonVersion = (text) => text.replace(/("version"\s*:\s*")\d+\.\d+\.\d+(")/g, `$1${version}$2`);

edit("gradle.properties", (t) => t.replace(/^version=.*$/m, `version=${version}`));
edit("plugin/.claude-plugin/plugin.json", jsonVersion);
edit("npm/package.json", jsonVersion);
edit("server.json", jsonVersion); // 최상위 version 과 packages[].version 모두

console.log("확인: .\\gradlew.bat build (버전 일치 검사 포함)");
