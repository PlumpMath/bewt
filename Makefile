
all:
	mkdir -p base/src/main/resources
	(cd pod; lein install)
	(cd aether; lein install; lein uberjar; cp target/aether-*-standalone.jar ../base/src/main/resources)
	(cd core; lein install)
	(cd base; make uberjar)
	echo '#!/usr/bin/env bash' > boot
	echo 'java $$JVM_OPTS -jar $$0 "$$@"' >> boot
	echo 'exit' >> boot
	cat base/target/base*-jar-with-dependencies.jar >> boot
	chmod 0755 boot
	@echo "*** Done. Created boot executable: ./boot ***"


