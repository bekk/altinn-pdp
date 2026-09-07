#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
    echo "Missing .env; run: cp .env.example .env" >&2
    exit 1
fi

set -a
. ./.env
set +a

exec ./gradlew :altinnpdp-restserver:run
