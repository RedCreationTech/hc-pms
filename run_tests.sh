#!/bin/bash
clojure -M -e '(do (load-file "test/run_tests.clj") (run-tests/run-all-tests))'
