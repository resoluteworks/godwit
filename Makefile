export OP_ACCOUNT := my.1password.com
include gradle.properties
-include .env
export

env:
	rm -f .env
	op read "op://Development/resolute-works-open-source/godwit.env.local" > .env

test:
	./gradlew clean test
	./gradlew atlasTest
	python3 scripts/coverage-gate.py
	./gradlew -p docs-snippets test
	./gradlew :coverallsJacoco

check-docs:
	scripts/check-docs.sh

publish-local:
	./gradlew publishToMavenLocal

publish:
	./gradlew publishAggregationToCentralPortal

release: test check-docs publish-local publish
	git tag v$(godwitVersion)
	git push origin v$(godwitVersion)
