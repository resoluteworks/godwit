#!/usr/bin/env bash
# Negative-compile checks for the godwit public API.
#
# Compiles every snippet in docs-snippets/neg/ on its own (module :neg-check of the docs-snippets build, against
# godwit-core and godwit-test only).
# The first line of each snippet states the expectation:
#   // expect: OK      the snippet must compile
#   // expect: <text>  the snippet must fail to compile with an error located in that snippet, and the compiler
#                      output must contain <text>
# Prints one line per snippet and exits 0 only when every expectation holds.
set -u
cd "$(dirname "$0")/../docs-snippets"

failures=0
mkdir -p build/neg-src build/neg-out
for snippet in neg/*.kt; do
  name=$(basename "$snippet" .kt)
  expect=$(head -1 "$snippet" | sed -n 's#^// expect: ##p')
  if [ -z "$expect" ]; then
    echo "FAIL $name: first line must be '// expect: ...'"
    failures=$((failures + 1))
    continue
  fi

  src="build/neg-src/$name"
  mkdir -p "$src"
  find "$src" -type f -name '*.kt' -delete
  cp "$snippet" "$src/"
  out="build/neg-out/$name.txt"
  ./gradlew --offline -q :neg-check:compileKotlin -PnegSnippet="$PWD/$src" >"$out" 2>&1
  code=$?

  if [ "$expect" = "OK" ]; then
    if [ $code -eq 0 ]; then
      echo "PASS $name: compiles"
    else
      echo "FAIL $name: expected to compile, exit $code (see $out)"
      failures=$((failures + 1))
    fi
  else
    # The error must be located in the snippet; the expected text may sit on the error line or on the candidate
    # lines the compiler prints under it.
    located=$(grep -E "^e: .*/${name}\.kt:" "$out" | head -1)
    matched=$(grep -F -- "$expect" "$out" | head -1 | sed 's/^ *//')
    if [ $code -ne 0 ] && [ -n "$located" ] && [ -n "$matched" ]; then
      echo "PASS $name: does not compile: ${matched#*.kt:}"
    else
      echo "FAIL $name: expected a compile error containing \"$expect\", exit $code (see $out)"
      failures=$((failures + 1))
    fi
  fi
done

total=$(ls neg/*.kt | wc -l | tr -d ' ')
echo "neg-check: $((total - failures))/$total expectations hold"
[ $failures -eq 0 ]
