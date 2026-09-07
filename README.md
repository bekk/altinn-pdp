# Altinn PDP

## Local secrets

Create the ignored local secrets file from the committed template:

```shell
cp .env.example .env
```

The template has no variables yet; they are added as the app starts reading them.
Fill in `.env` when it does, then start Ktor:

```shell
./scripts/dev.sh
```

Do not commit `.env` or print secrets in logs.
