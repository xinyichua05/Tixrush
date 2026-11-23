# Starts the TixRush backend on http://localhost:8080. Easiest way: run scripts\start-backend.cmd
#
# What it does:
#  1. Uses the portable Java 21.
#  2. Loads your secrets (Redis, RabbitMQ, Stripe) from your Windows user environment variables.
#     They are never printed. Set them once with:
#       [Environment]::SetEnvironmentVariable('NAME','value','User')
#  3. Tells Java and Maven to trust the same certificates Windows trusts (Windows-ROOT), because the
#     company network inspects HTTPS. Without this, Stripe payments fail. On a normal network it's harmless.

$env:JAVA_HOME = "$env:USERPROFILE\tools\jdk-21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

foreach ($n in 'REDIS_URL', 'RABBITMQ_URL', 'STRIPE_SECRET_KEY', 'STRIPE_WEBHOOK_SECRET') {
    $v = [Environment]::GetEnvironmentVariable($n, 'User')
    if ($v) { Set-Item "env:$n" $v.Trim().Trim('"').Trim("'") }
}
if (-not $env:REDIS_URL -or -not $env:RABBITMQ_URL) {
    Write-Warning 'REDIS_URL or RABBITMQ_URL is not set, so the backend will fail to connect.'
}
if (-not $env:STRIPE_SECRET_KEY) {
    Write-Host 'No STRIPE_SECRET_KEY: payments use the instant test Pay button.'
}

# Maven itself also downloads over HTTPS, so it gets the same trust setting (this hides the PKIX warnings).
$env:MAVEN_OPTS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'

Set-Location (Join-Path $PSScriptRoot '..\backend')
& .\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Djavax.net.ssl.trustStoreType=Windows-ROOT'
