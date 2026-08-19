.PHONY: up down seed logs build test

# GNU Make for Windows dispatches recipes through cmd.exe and does not honor
# a Makefile SHELL reassignment for that dispatch, so cmd chokes on `./mvnw`
# and `chmod`. Route recipes through Git Bash explicitly instead of relying
# on `bash` resolving on PATH — on machines with WSL installed, plain `bash`
# can resolve to the WSL launcher stub ahead of Git Bash.
ifeq ($(OS),Windows_NT)
BASH := "C:/Program Files/Git/bin/bash.exe"
else
BASH := bash
endif

up:
	docker compose up -d

down:
	docker compose down

seed: build
	@$(BASH) -c "chmod +x scripts/bootstrap.sh && ./scripts/bootstrap.sh"

build:
	$(BASH) -c "./mvnw install -DskipTests"

test:
	$(BASH) -c "./mvnw verify"

logs:
	docker compose logs -f
