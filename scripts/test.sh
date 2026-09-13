#!/usr/bin/env sh
set -eu
for service in identity-service customer-service ledger-service money-movement-service rail-simulator gateway; do
  ./mvnw -q -f "services/$service/pom.xml" test
done
(cd apps/web && npm test -- --run)

