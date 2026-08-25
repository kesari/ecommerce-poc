# Commerce Platform

Local platform for the complete e-commerce POC: storefront, BFF, Java services,
PostgreSQL, Kafka, Valkey, and observability.

## Run

```bash
docker compose up --build -d
docker compose ps
```

Open the storefront at `http://localhost:3000` and the BFF Swagger UI at
`http://localhost:8080/swagger-ui.html`.

Topic creation runs automatically in the `kafka-init` container once the broker
is healthy: 16 base topics from `kafka/topic-definitions.yaml`, each with
`retry.1`, `retry.2`, and `dlq` companions.

## Services and ports

| Component | Host port | Notes |
|---|---|---|
| PostgreSQL | 5432 | one database + role per service, created by `postgres/initialization/01-databases.sql` |
| Kafka | 9092 | KRaft single node; host listener `localhost:9092`, in-network `kafka:29092`; auto-create disabled |
| Valkey | 6379 | cache only, never authoritative |
| Jaeger UI | 16686 | traces arrive via OTel Collector OTLP export |
| OTel Collector | 4317 gRPC / 4318 HTTP | services send spans here |
| Prometheus | 9090 | scrapes `/actuator/prometheus` on each service |
| Commerce Web | 3000 | React storefront served by Nginx |
| Commerce BFF | 8080 | browser-facing API and Swagger UI |

## In-network endpoints for services

```text
postgres:5432   database <service>_service, user/password <service>_service
kafka:29092     bootstrap server
valkey:6379
otel-collector:4317
```

## Notes

- The Compose credentials and shared JWT secret are for local POC use only.
- Metrics use Spring Boot Actuator scraped directly by Prometheus.
- Traces flow through the collector into Jaeger; logs stay structured JSON on
  service stdout in this phase.
