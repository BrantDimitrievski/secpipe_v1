# Third-party notices

SecPipe is MIT-licensed (see `LICENSE`). It adapts material from, or depends on, the third-party works below.

## Cloudflare `security-audit-skill` (MIT)

Source: https://github.com/cloudflare/security-audit-skill (reference commit `c1c8a8c`).

SecPipe adapts design ideas and text from this project: agent prompts (hunter, disprover/refuter, coverage
critic), the verdict branches of the findings report schema (`secpipe-contracts/src/main/resources/schemas/`),
and validator rules (grounding and verdict consistency checks). Adapted files say so in a header or `$comment`.
No git history was copied.

Full licence text:

```
MIT License

Copyright (c) 2025-2026 Cloudflare, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## OWASP Benchmark for Java (GPL-2.0) — not vendored

The evaluation harness (`secpipe-eval`) scores SecPipe against the OWASP Benchmark. The Benchmark is
**downloaded at evaluation time and never vendored** into this repository or into any SecPipe artifact, so no
GPL-2.0 code is distributed with SecPipe.

## Build and runtime dependencies

Maven dependencies (Jackson, picocli, LangChain4j, LangGraph4j, PMD, JavaParser, networknt
json-schema-validator, Apache Commons CSV, SLF4J, JUnit, AssertJ, WireMock) are used under their own licences.
The pinned versions are listed in `docs/PLAN.md`, Appendix "Pinned versions". The shaded `secpipe.jar`
includes the runtime dependencies. Their licence files are kept under `META-INF/` in the jar.

The Maven Wrapper scripts (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`) come from Apache Maven
Wrapper 3.3.4 and are licensed under the Apache License, Version 2.0.

## OASIS SARIF 2.1.0 JSON schema (OASIS IPR; tests only)

Source: https://github.com/oasis-tcs/sarif-spec, file `sarif-2.1/schema/sarif-schema-2.1.0.json` at commit
`1742a253eb654bf8a4ed7c60eaef7412b2c23553` (sha256 `c3b4bb2d6093897483348925aaa73af03b3e3f4bd4ca38cef26dcb4212a2682e`).

A byte-identical copy is kept at `secpipe-report/src/test/resources/ca/carleton/secpipe/report/sarif-schema-2.1.0.json`
and is used only by tests to validate generated SARIF. It is not part of `secpipe.jar`. Copyright (c) OASIS Open.
