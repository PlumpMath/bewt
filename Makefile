.PHONY: prep base all

all: prep base

prep:
	rm -rf base/src/main/resources
	mkdir -p base/src/main/resources
	(cd pod; lein clean; lein install)
	(cd aether; lein clean; lein install; lein uberjar; cp target/aether-*-standalone.jar ../base/src/main/resources)
	(cd core; lein clean; lein install)

base:
	(cd base; mvn clean; make uberjar)
	echo '#!/usr/bin/env bash' > boot
	echo 'java $$JVM_OPTS -jar $$0 "$$@"' >> boot
	echo 'exit' >> boot
	cat base/target/base*-jar-with-dependencies.jar >> boot
	chmod 0755 boot
	@echo "*** Done. Created boot executable: ./boot ***"
