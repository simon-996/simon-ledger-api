# simon-ledger-api

Simon Ledger backend API.

## Git

- Remote: `git@github.com:simon-996/simon-ledger-api.git`
- Default branch: `master`
- Commit message style: `feat: ...`, `fix: ...`

## Local Config

`src/main/resources/application-dev.yml` is ignored by Git. Use `src/main/resources/application-prod.yml.example` as the production configuration template.

## Docker

Build image:

```bash
docker build -t simon-ledger-api:latest .
```

Run container:

```bash
docker run --rm -p 18080:18080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e JAVA_OPTS="-Xms256m -Xmx512m" \
  simon-ledger-api:latest
```
