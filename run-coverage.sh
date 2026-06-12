#!/bin/bash
cd /Users/kevinli/sandbox/rc/ruoyi_clojure

# Build classpath
CP=$(clojure -Spath -A:test)

# Run cloverage directly with test runner specified
java -cp "$CP" clojure.main -m cloverage.coverage \
  -p src/clj \
  -s test/clj \
  -o target/coverage \
  --html --emma-xml --text \
  -r '.*core.*' \
  --runner clojure.test
