#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Modified by Datazip Inc. in 2026

COMPOSE_DIR  := local-test
KIND_CLUSTER := fusion-cluster
AMORO_LOG_DIR ?= $(CURDIR)/docker/kind/amoro-logs
BIN_HOME     := $(CURDIR)/dist/src/main/fusion-bin

# Auto-detect JDK 17: prefer JAVA_HOME if already JDK 17,
# then macOS java_home utility, then common Linux paths.
JAVA_HOME_17 := $(shell \
  _cur="$(JAVA_HOME)"; \
  if [ -n "$$_cur" ] && "$$_cur/bin/java" -version 2>&1 | grep -q '"17\.'; then \
    echo "$$_cur"; \
  elif [ "$$(uname -s)" = "Darwin" ]; then \
    /usr/libexec/java_home -v 17 2>/dev/null || true; \
  else \
    for d in /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk \
              /usr/lib/jvm/temurin-17 /usr/lib/jvm/jdk-17; do \
      [ -x "$$d/bin/java" ] && echo "$$d" && break; \
    done; \
  fi)

MVN := JAVA_HOME="$(JAVA_HOME_17)" PATH="$(JAVA_HOME_17)/bin:$(PATH)" ./mvnw

.DEFAULT_GOAL := help

.PHONY: help build debug debug-deps debug-compile debug-server debug-optimizer debug-catalog \
        debug-data debug-stop debug-reset debug-clean vscode setup-debug-mode clean-debug-mode \
        start-deps stop-deps start-fusion-docker clean-fusion-docker \
        sync-frontend spotless-fix integration-test integration-test-build \
        integration-test-images integration-test-down

help:
	@echo "Fusion (Amoro) Development Makefile"
	@echo ""
	@echo "Development:"
	@echo "  make build                 Clean build + install to ~/.m2 (requires JDK 17)"
	@echo "  make sync-frontend         Sync built frontend assets to target/ (fixes blank UI)"
	@echo ""
	@echo "Debug (server and optimizer from compiled classes, no dist build):"
	@echo "  make debug                 Start Postgres + MinIO, create the $(DEBUG_DB) database, compile"
	@echo "  make debug-server          Run the server in the foreground (JDWP on :$(SERVER_DEBUG_PORT))"
	@echo "  make debug-optimizer       Run an optimizer in the foreground (JDWP on :$(OPTIMIZER_DEBUG_PORT))"
	@echo "  make debug-catalog         Register catalog $(DEBUG_CATALOG) on the local MinIO (server must run)"
	@echo "  make debug-data ARGS=...   Create an Iceberg table with data and delete files, e.g."
	@echo "                             ARGS=\"--files 20 --pos 20 --update\" (ARGS=--help lists options)"
	@echo "  make debug-stop            Stop the server and optimizer started from this checkout"
	@echo "  make vscode                Install VS Code launch, task and settings files into .vscode/"
	@echo "  make debug-reset           Drop and recreate the $(DEBUG_DB) database"
	@echo "  make debug-clean           Stop Postgres + MinIO"
	@echo "  make spotless-fix          Auto-fix Spotless (Google Java Format) violations"
	@echo ""
	@echo "Docker:"
	@echo "  make start-deps            Start Postgres and Minio for IDE debugging"
	@echo "  make stop-deps             Stop Postgres and Minio"
	@echo "  make start-fusion-docker   Start Kind cluster + all services + optimizer"
	@echo "  make clean-fusion-docker   Remove Kind cluster + services + volumes"
	@echo ""
	@echo "Integration tests (integration-tests/):"
	@echo "  make integration-test          Build, start the stack, run the suite (TEST_ONLY=<test> runs one test)"
	@echo "  make integration-test-build    Maven: distribution, Spark optimizer job, test sources"
	@echo "  make integration-test-images   Docker: fusion:test and fusion-spark:test from those artifacts"
	@echo "  make integration-test-down     Remove the test stack"
	@echo ""
	@echo "Access:"
	@echo "  Fusion Web UI : http://localhost:1630  (admin / password)"
	@echo "  MinIO Console : http://localhost:9001  (admin / password)"

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------

build:
	@if [ -z "$(JAVA_HOME_17)" ]; then \
		echo "ERROR: JDK 17 not found. Install JDK 17 or set JAVA_HOME."; \
		exit 1; \
	fi
	@echo "Using JDK 17: $(JAVA_HOME_17)"
	@find "$(BIN_HOME)/logs" -name 'optimizer-local-test-*' -exec rm -rf {} + 2>/dev/null || true
	@echo "Building all modules..."
	@$(MVN) clean install -DskipTests -Drat.skip=true -Dmaven.clean.failOnError=false -B -ntp

# ---------------------------------------------------------------------------
# Debug mode (IDE workflow)
# ---------------------------------------------------------------------------

# Server and optimizer run from compiled classes, not dist/ jars. See CONTRIBUTING.md, "Debug mode".

DEBUG_DB             ?= fusion_dev
DEBUG_CATALOG        ?= local_iceberg
DEBUG_GROUP          ?= spark-container
DEBUG_PARALLELISM    ?= 1
DEBUG_SUSPEND        ?= n
SERVER_DEBUG_PORT    ?= 5005
OPTIMIZER_DEBUG_PORT ?= 5006
OPTIMIZER_MEMORY_MB  ?= 2048
# Separate from target/, which an IDE with a stale project model fills with broken classes.
DEBUG_BUILD_DIR      := target-debug
DEBUG_CP             := $(CURDIR)/fusion-ams/$(DEBUG_BUILD_DIR)/debug-classpath.txt
DEBUG_COOKIES        := $(CURDIR)/fusion-ams/$(DEBUG_BUILD_DIR)/debug-cookies.txt
FUSION_URL           := http://localhost:1630

JAVA_17       := "$(JAVA_HOME_17)/bin/java"
JVM_ADD_OPENS := $(foreach p,java.lang java.lang.invoke java.lang.reflect java.io java.net java.nio \
                   java.util java.util.concurrent java.util.concurrent.atomic sun.nio.ch sun.nio.cs \
                   sun.security.action sun.util.calendar,--add-opens=java.base/$(p)=ALL-UNNAMED)
DEBUG_RUN_CP   = $(CURDIR)/fusion-ams/$(DEBUG_BUILD_DIR)/classes:$$(cat "$(DEBUG_CP)")
DEBUG_AWS_ENV := AWS_ACCESS_KEY_ID=admin AWS_SECRET_ACCESS_KEY=password AWS_REGION=us-east-1

debug: debug-deps
	@$(MAKE) --no-print-directory debug-compile
	@echo ""
	@echo "Debug setup ready. In separate terminals:"
	@echo "  make debug-server       server, debugger port $(SERVER_DEBUG_PORT)"
	@echo "  make debug-catalog      once, after the server is up"
	@echo "  make debug-optimizer    optimizer, debugger port $(OPTIMIZER_DEBUG_PORT)"

debug-deps: start-deps
	@echo "Waiting for Postgres..."
	@until docker exec postgres pg_isready -U iceberg >/dev/null 2>&1; do sleep 1; done
	@docker exec postgres psql -U iceberg -d postgres -Atc \
		"SELECT 1 FROM pg_database WHERE datname = '$(DEBUG_DB)'" | grep -q 1 \
		|| docker exec postgres createdb -U iceberg $(DEBUG_DB)

debug-compile:
	@if [ -z "$(JAVA_HOME_17)" ]; then echo "ERROR: JDK 17 not found. Set JAVA_HOME."; exit 1; fi
	@echo "Compiling..."
	@$(MVN) -q -B -ntp test-compile dependency:build-classpath -pl fusion-ams -am \
		-Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true -Dcheckstyle.skip=true \
		-Dfusion.build.dir=$(DEBUG_BUILD_DIR) -Dmdep.outputFile="$(DEBUG_CP)"

debug-server: $(if $(NO_COMPILE),,debug-compile)
	@echo "Starting Fusion on $(FUSION_URL) (admin / password), database $(DEBUG_DB)"
	@AMORO_HOME="$(BIN_HOME)" AMORO_CONF_DIR="$(CURDIR)/$(COMPOSE_DIR)" CONSOLE_LOG_LEVEL=info \
		AMS_SERVER__EXPOSE__HOST=127.0.0.1 \
		OPTIMIZER_LOG_CONF_FILE="$(BIN_HOME)/conf/optimize/log4j2.xml" \
		AMS_DATABASE_URL="jdbc:postgresql://localhost:5432/$(DEBUG_DB)" \
		$(DEBUG_AWS_ENV) \
		$(JAVA_17) -Dfile.encoding=UTF-8 -Darrow.memory.allocator=unsafe -XX:+UseZGC \
		-XX:CompileCommand=exclude,io/netty/buffer/PoolChunkList.allocate \
		-XX:CompileCommand=exclude,io/netty/buffer/PoolArena.allocate $(JVM_ADD_OPENS) \
		-agentlib:jdwp=transport=dt_socket,server=y,suspend=$(DEBUG_SUSPEND),address=*:$(SERVER_DEBUG_PORT) \
		-cp "$(DEBUG_RUN_CP)" io.olake.fusion.server.FusionServiceContainer

# Registers into the bootstrap group (optimizer.bootstrap.group-name), which accepts external optimizers.
debug-optimizer: $(if $(NO_COMPILE),,debug-compile)
	@echo "Starting optimizer for group $(DEBUG_GROUP), parallelism $(DEBUG_PARALLELISM)"
	@$(DEBUG_AWS_ENV) $(JAVA_17) -Xmx$(OPTIMIZER_MEMORY_MB)m $(JVM_ADD_OPENS) \
		-agentlib:jdwp=transport=dt_socket,server=y,suspend=$(DEBUG_SUSPEND),address=*:$(OPTIMIZER_DEBUG_PORT) \
		-cp "$(DEBUG_RUN_CP)" io.olake.fusion.optimizer.standalone.StandaloneOptimizer \
		-a thrift://127.0.0.1:1261 -g $(DEBUG_GROUP) -p $(DEBUG_PARALLELISM)

debug-catalog:
	@curl -sf -c "$(DEBUG_COOKIES)" -X POST "$(FUSION_URL)/api/ams/v1/login" \
		-H "X-Request-Source: Web" -H "Content-Type: application/json" \
		-d '{"user":"admin","password":"password"}' >/dev/null \
		|| { echo "ERROR: cannot log in to $(FUSION_URL); is 'make debug-server' running?"; exit 1; }
	@if curl -sf -b "$(DEBUG_COOKIES)" -H "X-Request-Source: Web" "$(FUSION_URL)/api/ams/v1/catalogs" \
		| grep -q '"catalogName":"$(DEBUG_CATALOG)"'; then \
		echo "Catalog $(DEBUG_CATALOG) already exists."; \
	else \
		curl -s -b "$(DEBUG_COOKIES)" -X POST "$(FUSION_URL)/api/ams/v1/catalogs" \
			-H "X-Request-Source: Web" -H "Content-Type: application/json" \
			-d '{"name":"$(DEBUG_CATALOG)","type":"custom","optimizerGroup":"$(DEBUG_GROUP)", \
			"tableFormatList":["ICEBERG"], \
			"storageConfig":{"storage.type":"S3","storage.s3.endpoint":"http://localhost:9000","storage.s3.region":"us-east-1"}, \
			"authConfig":{"auth.type":"AKSK","auth.aksk.access-key":"admin","auth.aksk.secret-key":"password"}, \
			"properties":{"catalog-impl":"org.apache.iceberg.jdbc.JdbcCatalog", \
			"uri":"jdbc:postgresql://localhost:5432/$(DEBUG_DB)","jdbc.user":"iceberg","jdbc.password":"password", \
			"jdbc.schema-version":"V1","warehouse":"s3://warehouse/$(DEBUG_CATALOG)/","s3.path-style-access":"true"}, \
			"tableProperties":{}}'; \
		echo ""; \
	fi

debug-data: $(if $(NO_COMPILE),,debug-compile)
	@$(DEBUG_AWS_ENV) $(JAVA_17) $(JVM_ADD_OPENS) \
		-cp "$(CURDIR)/fusion-ams/$(DEBUG_BUILD_DIR)/test-classes:$(DEBUG_RUN_CP)" \
		io.olake.fusion.dev.IcebergTestDataGenerator \
		--catalog $(DEBUG_CATALOG) --uri "jdbc:postgresql://localhost:5432/$(DEBUG_DB)" $(ARGS)

# Force-kills after 10 seconds: the server does not always exit on SIGTERM.
debug-stop:
	@pids=$$(ps -eo pid,args | grep -E "(FusionServiceContainer|StandaloneOptimizer)" \
		| grep -F "$(CURDIR)/" | grep -v grep | awk '{print $$1}'); \
	if [ -z "$$pids" ]; then echo "Nothing to stop."; exit 0; fi; \
	kill $$pids 2>/dev/null; \
	for i in 1 2 3 4 5 6 7 8 9 10; do \
		alive=$$(for p in $$pids; do kill -0 $$p 2>/dev/null && echo $$p; done); \
		[ -z "$$alive" ] && break; sleep 1; \
	done; \
	[ -n "$$alive" ] && kill -9 $$alive 2>/dev/null; \
	echo "Stopped: $$pids"

vscode:
	@mkdir -p .vscode
	@for f in launch.json tasks.json; do \
		if [ -f ".vscode/$$f" ] && ! cmp -s "dev/vscode/$$f" ".vscode/$$f"; then \
			cp ".vscode/$$f" ".vscode/$$f.bak"; echo "Saved the old .vscode/$$f as .vscode/$$f.bak"; \
		fi; \
		cp "dev/vscode/$$f" ".vscode/$$f"; \
	done
	@if [ -f .vscode/settings.json ]; then \
		echo "Kept .vscode/settings.json; compare with dev/vscode/settings.json"; \
	else \
		sed "s|@JAVA_HOME_17@|$(JAVA_HOME_17)|g" dev/vscode/settings.json > .vscode/settings.json; \
		echo "Wrote .vscode/settings.json for JDK $(JAVA_HOME_17)"; \
	fi
	@echo "Done. Reload VS Code, then pick a 'Fusion:' configuration in Run and Debug."

debug-reset:
	@docker exec postgres dropdb -U iceberg --if-exists $(DEBUG_DB)
	@docker exec postgres createdb -U iceberg $(DEBUG_DB)
	@echo "Database $(DEBUG_DB) recreated."

debug-clean: stop-deps
	@rm -f "$(DEBUG_CP)" "$(DEBUG_COOKIES)"
	@echo "Teardown complete. The $(DEBUG_DB) database is kept; 'make debug-reset' clears it."

# Older names of the debug targets.
setup-debug-mode: debug
clean-debug-mode: debug-clean

# ---------------------------------------------------------------------------
# Docker
# ---------------------------------------------------------------------------

start-deps:
	@echo "Starting local dependencies (Postgres & Minio)..."
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml --profile dev up -d

stop-deps:
	@echo "Stopping local dependencies..."
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml --profile dev down

start-fusion-docker:
	@echo "Starting Fusion (Kind cluster + all services)..."
	@mkdir -p "$(AMORO_LOG_DIR)"
	@AMORO_LOG_DIR="$(AMORO_LOG_DIR)" docker compose -f $(COMPOSE_DIR)/docker-compose.yml --profile prod up -d
	@kind export kubeconfig --name $(KIND_CLUSTER) 2>/dev/null

clean-fusion-docker:
	@kind delete cluster --name $(KIND_CLUSTER) 2>/dev/null || true
	@kind delete cluster --name fusion-spark-cluster 2>/dev/null || true
	@docker compose -f $(COMPOSE_DIR)/docker-compose.yml --profile prod down -v
	@echo "Teardown complete."

# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------

spotless-fix:
	@$(MVN) spotless:apply -B -ntp

sync-frontend:
	@SRC=fusion-web/src/main/resources/static; \
	if [ ! -d "$$SRC" ]; then \
		echo "ERROR: $$SRC not found. Run 'pnpm build' inside fusion-web/ first."; \
		exit 1; \
	fi; \
	for DST in fusion-web/target/classes/static fusion-web/$(DEBUG_BUILD_DIR)/classes/static; do \
		mkdir -p "$$DST"; cp -r "$$SRC"/. "$$DST"/; \
	done
	@echo "Frontend synced. Refresh http://localhost:1630"

# ---------------------------------------------------------------------------
# Integration tests (integration-tests/)
# ---------------------------------------------------------------------------

# Single entry point, locally and in CI. `make integration-test` builds artifacts and images
# itself unless FUSION_IMAGE and FUSION_SPARK_IMAGE pin them.
integration-test:
	./integration-tests/scripts/run.sh

# Build phases, run as separate CI steps (see integration-tests/scripts/build.sh).
integration-test-build:
	./integration-tests/scripts/build.sh artifacts

integration-test-images:
	./integration-tests/scripts/build.sh images

integration-test-down:
	./integration-tests/scripts/stack.sh down
