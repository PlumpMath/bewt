
uberjar:
	mvn assembly:assembly -DdescriptorId=jar-with-dependencies

deploy:
	scp pom.xml target/base-2.0.0-SNAPSHOT.jar clojars@clojars.org:
