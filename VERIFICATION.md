# Проверка поставки

Дата: 20 октября 2026, Europe/Moscow.

## Сборка и тесты

Выполнена чистая сборка `mvn clean verify`:

* Java: Eclipse Temurin 21.0.12.1.
* Maven: 3.9.6; включённый Wrapper отдельно проверен и запускает Maven 3.9.9.
* Docker Engine: 29.7.2.
* PostgreSQL: `postgres:16-alpine`, настоящий контейнер Testcontainers.
* HTTP Binance: WireMock, без обращения к бирже.

| Набор | Тестов | Ошибок | Пропущено |
|---|---:|---:|---:|
| BinanceClientTest | 11 | 0 | 0 |
| PricePollerTest | 2 | 0 | 0 |
| PriceServiceTest | 7 | 0 | 0 |
| PriceApiIT | 4 | 0 | 0 |
| Всего | **24** | **0** | **0** |

Итог: **BUILD SUCCESS**. Выполнение полного финального прогона — 34,698 секунды.
Текстовые отчёты Maven приложены в `docs/test-results/`.

На машине проверки для Maven использованы отдельный локальный cache и прямой
Maven Central вместо пользовательского mirror. Эти машинные настройки не включены
в проект и не требуются для его сборки.

GitHub Actions подготовлен, но на GitHub ещё не запускался: push не выполнялся.

## Docker

* `docker compose config --quiet` — успешно.
* `docker compose build` — успешно; Maven 3.9.9, Java 21, финальный JRE-образ.
* Собранный образ запущен вместе с PostgreSQL и WireMock на отдельном тестовом порту.
* Flyway применил миграцию, `/actuator/health` вернул `UP`.
* С включённым scheduler и `BINANCE_POLL_INTERVAL=PT1S` накоплено 8 записей
  к моменту проверки истории; в логах подтверждены последующие опросы.
* latest и average сохранили точное значение `10.200000000000000001`.
* history: `totalElements=8`, `hasNext=true` при размере страницы 1.
* trend: `FLAT`, как ожидается для постоянной тестовой цены.
* Итог: **DOCKER SMOKE PASSED**. Временные контейнеры и тестовый HTTP-сервер остановлены.

Внешний Binance в проверках не вызывался: доступность биржи из сети пользователя
и её региональные ограничения не являются результатом этих тестов.
