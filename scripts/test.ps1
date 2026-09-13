$ErrorActionPreference = "Stop"
$services = @("identity-service", "customer-service", "ledger-service", "money-movement-service", "rail-simulator", "gateway")
foreach ($service in $services) {
  & .\mvnw.cmd -q -f "services/$service/pom.xml" test
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
Push-Location apps/web
npm test -- --run
Pop-Location

