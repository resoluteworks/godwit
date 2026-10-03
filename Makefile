include gradle.properties
-include .env
export

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
