# SecPipe live demo

The demo slice from WO-DEMO.1 has been replaced by the real `secpipe scan` pipeline (WO-K.13).
The full demo script and explanations are in [`docs/user/guide.md`](user/guide.md) §6 and
`scripts/demo/demo.sh`.

Quick version, from the repository root (JDK 21; the first build needs Maven Central):

```bash
./mvnw -q -B -DskipTests package
bash scripts/demo/demo.sh              # no-AI scan, fake-LLM scan, and a sandbox scan if Docker is available
bash scripts/demo/demo.sh --no-docker  # skip the sandbox step
```

On `fixtures/e2e/sqli-min` you should see two candidates:
- `LoginServlet` SQL injection: `needs_validation` without Docker, `confirmed` by a generated regression test with Docker.
- `TagFilter`: `rejected` by the deterministic refuter (the value is overwritten with a constant).
