# Altinn PDP

## Local secrets

Create the ignored local secrets file from the committed template:

```shell
cp .env.example .env
```

Fill in `.env`, then start Ktor:

```shell
./scripts/dev.sh
```

Do not commit `.env` or print secrets in logs.
