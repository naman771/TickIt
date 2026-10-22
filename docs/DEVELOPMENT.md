# Local development

Use JDK 21, Maven 3.9+, PostgreSQL, and Redis. The Compose file uses PostgreSQL 16 and Redis 7. Local validation also ran on PostgreSQL 18.

```bash
createdb tickit
export DB_USER="$(whoami)"
# Set DB_PASS if required by your PostgreSQL installation.
mvn spring-boot:run
```

Open `http://localhost:8080`. Redis should be available on port 6379. Flyway owns schema changes; JPA validates the result.

## Tests

Tests use PostgreSQL database `tickit_test` and Redis logical database 15. Use disposable infrastructure rather than a Redis database holding unrelated data.

```bash
createdb tickit_test
export DB_USER="$(whoami)"
mvn verify
mvn test -Dtest=BookingConcurrencyTest
```

## Configuration

| Environment variable | Default |
|---|---|
| `DB_HOST`, `DB_PORT` | `localhost`, `5432` |
| `DB_NAME` | `tickit` |
| `DB_USER`, `DB_PASS` | `tickit`, empty |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` |
| `SERVER_PORT` | `8080` |
| `TEST_DATABASE_URL` | `jdbc:postgresql://localhost:5432/tickit_test` |
| `EMAIL_ENABLED` | `false` |
| `SMTP_HOST`, `SMTP_PORT` | `smtp.gmail.com`, `587` |
| `SMTP_USER`, `SMTP_PASS` | empty |
| `EMAIL_FROM` | `noreply@tickit.live` |

Spring properties also support standard environment overrides, such as `APP_SEAT_HOLD_TTL_SECONDS=10` for a short expiration demonstration. Optional SMTP health checks are disabled when email is disabled.

Compose supplies its own demo database credentials and binds exposed services to localhost. Never commit secrets; `.env` files are ignored.

## Compose lifecycle

```bash
docker compose logs -f app
docker compose down
```

Volumes persist after `down`. Use `docker compose down -v` only when deliberately deleting all disposable demo bookings and database data.
