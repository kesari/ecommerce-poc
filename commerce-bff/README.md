# commerce-bff

Part of the e-commerce POC estate. See
`../../cross-repo-impact-study/docs/fixtures/ecommerce-microservices-poc-design.md`.

## Build and test

```bash
./mvnw verify
```

## Run locally

```bash
docker compose -f ../commerce-platform/compose.yaml up -d postgres kafka valkey
SERVER_PORT=8080 ./mvnw spring-boot:run
```
