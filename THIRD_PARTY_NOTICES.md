# 제3자 고지 (Third-Party Notices)

## Sakila 샘플 데이터베이스

이 프로그램의 실행 파일(jar)에는 데모용으로 Sakila 샘플 데이터베이스의 SQLite판이 들어 있습니다.
빌드할 때 아래 저장소의 고정 커밋에서 받아 SHA-256으로 확인한 뒤 SQLite 파일로 변환합니다.
변환할 때 `last_update` 값을 덮어쓰는 트리거는 지웠고, 데이터는 바꾸지 않았습니다.

- 출처: https://github.com/jOOQ/sakila (커밋 `e089a5b1ec9af0df7a9c6a5d47d49fa1736a4e84`, `sqlite-sakila-db/`)
- 원본: MySQL AB 문서팀의 Mike Hillyer가 만든 MySQL용 Sakila 샘플 데이터베이스(BSD 라이선스)
- SQLite 포팅: DB Software Laboratory (http://www.etl-tools.com), BSD 라이선스

jOOQ/sakila 저장소의 라이선스 전문:

```
BSD 2-Clause License

Copyright (c) 2021, jOOQ Object Oriented Querying
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
