.PHONY: up down seed logs build test

up:
	docker compose up -d

down:
	docker compose down

seed:
	@chmod +x scripts/bootstrap.sh && ./scripts/bootstrap.sh

build:
	./mvnw install -DskipTests

test:
	./mvnw verify

logs:
	docker compose logs -f
