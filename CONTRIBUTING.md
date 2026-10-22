# Contributing

Use JDK 21, Maven, PostgreSQL, and Redis. Follow the local setup in the README and keep tests on `tickit_test` with Redis database 15.

Before opening a pull request:

```bash
mvn verify
bash -n scripts/rate_limit_probe.sh
docker compose config --quiet
```

Include the problem, resulting behavior, and validation evidence in the PR description. Add regression coverage for transaction or concurrency changes. Keep credentials, build outputs, and local IDE files out of Git. Never edit an applied Flyway migration; add a new versioned migration instead.
