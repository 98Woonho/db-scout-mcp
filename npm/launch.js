#!/usr/bin/env node
// db-scout MCP 서버의 npm 실행기. `npx db-scout-mcp` 로 실행된다.
//
// 서버는 Java 프로그램(jar)이라 npm 패키지에는 이 실행기만 들어 있다. 하는 일은 플러그인 실행기
// (plugin/bin/Launch.java)와 같다. 두 실행기가 같은 주소·해시 확인·보관 위치를 쓰는지 PluginFilesTest 가 검사한다.
//  1. 저장소 안에서 실행되면(개발) 로컬에서 빌드한 build/libs/db-scout-mcp.jar 를 쓴다.
//  2. 아니면 이 패키지 버전과 같은 GitHub Release 에서 jar 를 받아 SHA-256 을 확인하고
//     ~/.db-scout/jars/<버전>/ 에 보관한다. 다음부터는 받지 않는다(플러그인 실행기와 함께 쓴다).
//  3. java -jar 로 서버를 실행하고 표준입출력을 그대로 넘긴다. 접속 정보는 환경변수(DB_SCOUT_URL 등)로 받는다.
//
// 표준출력은 MCP 메시지 전용이므로 이 실행기의 안내는 모두 표준에러로 낸다.
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { createWriteStream, existsSync, mkdirSync, readFileSync, renameSync, rmSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";

const REPO = "98Woonho/db-scout-mcp";
const JAR = "db-scout-mcp.jar";
const MIN_JAVA = 21;

const here = dirname(fileURLToPath(import.meta.url));

function log(message) {
  process.stderr.write(`[db-scout] ${message}\n`);
}

function fail(message) {
  log(message);
  process.exit(1);
}

/** JAVA_HOME 이 있으면 그 java, 아니면 PATH 의 java. 21 이상인지 확인한다. */
function findJava() {
  const exe = process.platform === "win32" ? "java.exe" : "java";
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", exe) : "java";
  const res = spawnSync(java, ["-version"], { encoding: "utf8" });
  if (res.error) {
    fail(`Java ${MIN_JAVA} 이상이 필요합니다. java 를 실행하지 못했습니다(${java}). https://adoptium.net 에서 설치하세요.`);
  }
  // java -version 은 표준에러에 'version "21.0.1"' 또는 'version "1.8.0_392"' 형식으로 쓴다
  const m = /version "(\d+)(?:\.(\d+))?/.exec(`${res.stderr}${res.stdout}`);
  const feature = m ? (m[1] === "1" ? Number(m[2]) : Number(m[1])) : NaN;
  if (!(feature >= MIN_JAVA)) {
    fail(`Java ${MIN_JAVA} 이상이 필요합니다. 지금 Java: ${m ? m[0] : "알 수 없음"} (${java})`);
  }
  return java;
}

/** 저장소 안의 npm/ 폴더에서 실행되면 로컬 빌드 jar. npx 로 받은 패키지 위에는 저장소가 없다. */
function localBuild() {
  const repo = dirname(here);
  const settings = join(repo, "settings.gradle.kts");
  if (!existsSync(settings) || !readFileSync(settings, "utf8").includes('"db-scout-mcp"')) return null;
  const jar = join(repo, "build", "libs", JAR);
  if (!existsSync(jar)) {
    fail(`저장소 안에서 실행 중인데 빌드된 jar 가 없습니다: ${jar} (gradlew bootJar 를 먼저 실행하세요)`);
  }
  log(`저장소의 로컬 빌드를 실행합니다: ${jar}`);
  return jar;
}

async function download(url) {
  let res;
  try {
    res = await fetch(url, { redirect: "follow", signal: AbortSignal.timeout(5 * 60_000) });
  } catch (e) {
    fail(`내려받기 실패(${e.cause?.code ?? e.name}): ${url} (인터넷 연결을 확인하세요)`);
  }
  if (res.status !== 200) {
    fail(`내려받기 실패(HTTP ${res.status}): ${url} (인터넷 연결이나 패키지 버전을 확인하세요)`);
  }
  return res;
}

/** ~/.db-scout/jars/<버전>/db-scout-mcp.jar. 없으면 GitHub Release 에서 받아 해시를 확인한다. */
async function cachedRelease(version) {
  const dir = join(homedir(), ".db-scout", "jars", version);
  const jar = join(dir, JAR);
  if (existsSync(jar)) return jar;

  const base = `https://github.com/${REPO}/releases/download/v${version}/`;
  log(`db-scout ${version} 서버를 처음 한 번 내려받습니다(약 40MB)...`);
  const expected = (await (await download(base + JAR + ".sha256")).text()).trim().split(/\s+/)[0].toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(expected)) fail(`해시 파일 형식이 올바르지 않습니다: ${base}${JAR}.sha256`);

  mkdirSync(dir, { recursive: true });
  const tmp = join(dir, `download-${process.pid}-${Date.now()}.tmp`);
  try {
    const res = await download(base + JAR);
    const hash = createHash("sha256");
    const tap = new Transform({
      transform(chunk, _enc, done) {
        hash.update(chunk);
        done(null, chunk);
      },
    });
    await pipeline(Readable.fromWeb(res.body), tap, createWriteStream(tmp));
    const actual = hash.digest("hex");
    if (actual !== expected) fail(`내려받은 파일의 해시가 다릅니다. 예상 ${expected}, 실제 ${actual}`);
    try {
      renameSync(tmp, jar);
    } catch (e) {
      // 동시에 실행된 다른 실행기가 먼저 옮겨 놓았으면 그것을 쓴다
      if (!existsSync(jar)) throw e;
    }
  } finally {
    rmSync(tmp, { force: true });
  }
  log(`받았습니다: ${jar}`);
  return jar;
}

async function main() {
  const java = findJava();
  const { version } = JSON.parse(readFileSync(join(here, "package.json"), "utf8").replace(/^﻿/, ""));
  const jar = localBuild() ?? (await cachedRelease(version));

  // 남은 인자는 서버에 그대로 넘긴다. 예) --spring.config.additional-location=file:C:/tools/db-scout.yml
  const child = spawn(java, ["-jar", jar, ...process.argv.slice(2)], { stdio: "inherit" });
  for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"]) {
    process.on(signal, () => child.kill(signal));
  }
  child.on("error", (e) => fail(`서버를 실행하지 못했습니다: ${e.message}`));
  child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
}

main().catch((e) => fail(`예상하지 못한 오류: ${e.name}: ${e.message}`));
