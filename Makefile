.PHONY: pod aether core base

all: pod aether core boot

pod:
	(cd pod; lein clean; lein install)

aether:
	(cd aether; lein clean; lein install; lein uberjar)
	mkdir -p base/src/main/resources
	rm -f base/src/main/resources/aether-*-standalone.jar
	cp aether/target/aether-*-standalone.jar base/src/main/resources/

core:
	(cd core; lein clean; lein install)

base:
	(cd base; mvn clean; mvn assembly:assembly -DdescriptorId=jar-with-dependencies)

boot: base
	echo '#!/usr/bin/env bash' > boot
	echo 'java $$JVM_OPTS -jar $$0 "$$@"' >> boot
	echo 'exit' >> boot
	cat base/target/base*-jar-with-dependencies.jar >> boot
	chmod 0755 boot
	@echo "*** Done. Created boot executable: ./boot ***"
