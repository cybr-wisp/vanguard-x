.PHONY: build test ui-build docker-build up up-d down logs ps clean

build:
	mvn -B clean package -DskipTests

test:
	mvn -B clean verify -pl '!integration-tests'

ui-build:
	cd vanguard-ui && npm ci && npm run build

docker-build:
	docker compose build

up:
	docker compose up --build

up-d:
	docker compose up --build -d

down:
	docker compose down --remove-orphans

logs:
	docker compose logs -f

ps:
	docker compose ps

clean:
	mvn clean
	docker compose down --remove-orphans
