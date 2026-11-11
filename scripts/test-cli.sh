#!/usr/bin/env bash
# E2E con las imágenes :ci construidas desde el código actual, nunca desde GHCR.
set -euo pipefail

workers=3
# Referencias locales del E2E. No forman parte de la configuración de producción.
worker_image=bridge-worker:ci
orchestrator_image=bridge-orchestrator:ci
check_failure=false
via_host=false
use_profile=false
while (($#)); do
    case "$1" in
        --workers)
            [[ $# -ge 2 && $2 =~ ^[1-8]$ ]] || { echo '--workers necesita un valor de 1 a 8' >&2; exit 1; }
            workers=$2; shift 2 ;;
        --check-failure) check_failure=true; shift ;;
        --via-host) via_host=true; shift ;;
        --use-profile) use_profile=true; shift ;;
        -h|--help)
            echo 'Uso: bash scripts/test-cli.sh [--workers 1..8] [--check-failure] [--via-host] [--use-profile]'
            echo 'Requiere Linux/WSL, Node >=22.12, Docker Compose >=2.24.4, AWS CLI y jq.'
            echo 'Construir antes la CLI y las imágenes bridge-worker:ci y bridge-orchestrator:ci.'
            echo 'Prueba 105 documentos, particiones de 10 y recursos Compose aislados. No borra imágenes.'
            exit 0 ;;
        *) echo "Opción desconocida: $1" >&2; exit 1 ;;
    esac
done

die() { echo "ERROR: $*" >&2; exit 1; }
for tool in node docker aws jq; do
    command -v "$tool" >/dev/null || die "Falta $tool dentro de Linux/WSL"
done
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cli="$repository/bridge-dsl/packages/cli/bin/cli.js"
[[ -f "$repository/bridge-dsl/packages/cli/dist/cli.js" ]] || die 'Compila primero la CLI en bridge-dsl/'
docker info >/dev/null || die 'Docker no está disponible'
docker image inspect "$worker_image" "$orchestrator_image" >/dev/null || die 'Construye primero las dos imágenes :ci'

umask 077
project="brg-e2e-$(node -e 'process.stdout.write(require("node:crypto").randomUUID())')"
directory="$repository/scripts/test-results/$project"
mkdir -p "$directory/home"
# HOME se aísla para que el estado de brg no se mezcle con ejecuciones del usuario.
# Docker conserva su configuración original, incluso cuando se cambia HOME.
export DOCKER_CONFIG="${DOCKER_CONFIG:-$HOME/.docker}"
export AWS_ACCESS_KEY_ID=dummy AWS_SECRET_ACCESS_KEY=dummy AWS_EC2_METADATA_DISABLED=true
unset AWS_SESSION_TOKEN AWS_PROFILE

# Reutilizar los servicios de Compose, quitando puertos fijos y volúmenes de desarrollo.
cat >"$directory/compose.override.yaml" <<'COMPOSE'
services:
  mongodb:
    ports: !override ["0:27017"]
    volumes: !override []
  dynamodb-local:
    ports: !override ["0:8000"]
    volumes: !override []
  elasticmq:
    ports: !override ["0:9324"]
volumes: !reset {}
COMPOSE

compose() {
    docker compose --project-directory "$repository" -p "$project" \
        -f "$repository/docker-compose.yaml" -f "$directory/compose.override.yaml" "$@"
}

# Solo brg recibe el perfil de prueba. AWS CLI sigue usando las credenciales dummy.
brg() (
    export HOME="$directory/home"
    # Inyección interna de las dos imágenes; nunca hay fallback a GHCR.
    export BRG_TEST_WORKER_IMAGE="$worker_image"
    export BRG_TEST_ORCHESTRATOR_IMAGE="$orchestrator_image"
    if [[ $use_profile == true ]]; then
        unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
        export AWS_PROFILE=bridge-cli-test
        export AWS_SHARED_CREDENTIALS_FILE="$directory/test-credentials"
        export AWS_CONFIG_FILE="$directory/test-aws-config"
    fi
    node "$cli" "$@"
)

cleanup() {
    local status=$?
    local failed=false
    trap - EXIT
    compose logs --no-color >"$directory/services.log" 2>&1 || true
    for manifest in "$directory/home/.brg/runs"/brg-*/run.json; do
        [[ -f $manifest ]] || continue
        local id
        id=$(jq -er '.id' "$manifest") || { failed=true; continue; }
        brg cleanup "$id" >>"$directory/cleanup.log" 2>&1 || failed=true
    done
    # El nombre de proyecto es exclusivo y el override no usa volúmenes externos.
    compose down --volumes --remove-orphans >"$directory/compose-cleanup.log" 2>&1 || failed=true
    if [[ $status == 0 && $failed == true ]]; then status=1; fi
    if [[ $status != 0 ]]; then
        echo 'ERROR: E2E fallido. Diagnósticos de limpieza:' >&2
        cat "$directory/compose-cleanup.log" >&2
        for log in "$directory/runtime-logs"/brg-*/*.log; do
            [[ -f $log ]] || continue
            echo "--- $log ---" >&2
            tail -n 100 "$log" >&2
        done
    fi
    echo "Diagnósticos conservados en: $directory"
    if [[ $status == 0 ]]; then
        echo "PASS: 105 documentos, 11 WorkUnits, $workers workers configurados; ver evidence.json para saber cuáles procesaron trabajo."
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

local_aws() {
    local endpoint=$1
    shift
    [[ $endpoint =~ ^http://localhost:[0-9]+$ ]] || die 'El test solo permite endpoints AWS locales'
    aws --endpoint-url "$endpoint" --region us-east-1 --no-cli-pager \
        --cli-connect-timeout 3 --cli-read-timeout 5 --output json "$@"
}

wait_until_ready() {
    local deadline=$((SECONDS + 90))
    until "$@" >"$directory/readiness.log" 2>&1; do
        if ((SECONDS >= deadline)); then cat "$directory/readiness.log" >&2; die 'Servicio no disponible'; fi
        sleep 2
    done
}

host_port() {
    local container
    container=$(compose ps -q "$1")
    [[ -n $container ]] || die "No se encuentra el servicio $1"
    docker inspect "$container" | jq -er --arg port "$2" '.[0].NetworkSettings.Ports[$port][0].HostPort'
}

echo '1. Preparar emuladores aislados'
compose config --format json >"$directory/compose.json"
# Evitar que una futura modificación de Compose conecte el test a datos externos.
jq -e '(.volumes // {} | length) == 0 and
    ([.services.mongodb.volumes[]?, .services["dynamodb-local"].volumes[]?] | length) == 0' \
    "$directory/compose.json" >/dev/null || die 'El test no debe montar volúmenes de desarrollo'
docker image inspect "$worker_image" "$orchestrator_image" >"$directory/images.json"
compose up -d mongodb dynamodb-local elasticmq
network=$(jq -er '.networks.default.name' "$directory/compose.json")
mongo_port=$(host_port mongodb 27017/tcp)
dynamo_port=$(host_port dynamodb-local 8000/tcp)
sqs_port=$(host_port elasticmq 9324/tcp)
dynamo_endpoint="http://localhost:$dynamo_port"
sqs_endpoint="http://localhost:$sqs_port"
wait_until_ready compose exec -T mongodb mongosh --quiet --eval 'if (db.adminCommand({ping:1}).ok !== 1) quit(1)'
wait_until_ready local_aws "$dynamo_endpoint" dynamodb list-tables
wait_until_ready local_aws "$sqs_endpoint" sqs list-queues

echo '2. Preparar 105 documentos, una tabla vacía y la cola'
local_aws "$dynamo_endpoint" dynamodb create-table --table-name BridgeCliTest \
    --attribute-definitions AttributeName=id,AttributeType=S \
    --key-schema AttributeName=id,KeyType=HASH --billing-mode PAY_PER_REQUEST >/dev/null
local_aws "$dynamo_endpoint" dynamodb wait table-exists --table-name BridgeCliTest
queue_url=$(local_aws "$sqs_endpoint" sqs create-queue --queue-name migration-work | jq -er '.QueueUrl')
queue_path="/$(echo "$queue_url" | cut -d '/' -f 4-)"
compose exec -T mongodb mongosh --quiet --eval "
    db.getSiblingDB('bridge_cli_test').products.insertMany(
        Array.from({length: 105}, (_, i) => ({
            _id: 'item-' + (i + 1), name: 'Product ' + (i + 1), price: '12.50', stock: '3'
        }))
    )" >/dev/null

cat >"$directory/migration.brg" <<'MODEL'
bridge CliSmoke {
  source MongoDB { database "bridge_cli_test" collection "products" }
  target DynamoDB { table "BridgeCliTest" partitionKey "id" }
  sourceTypes { price string stock string }
  mapping {
    _id -> id
    name -> name
    price -> price using toDecimal
    stock -> stock using toInteger
  }
}
MODEL
mongo_uri='mongodb://mongodb:27017'
dynamo_service='http://dynamodb-local:8000'
sqs_service='http://elasticmq:9324'
if [[ $via_host == true ]]; then
    network=bridge
    mongo_uri="mongodb://host.docker.internal:$mongo_port"
    dynamo_service="http://host.docker.internal:$dynamo_port"
    sqs_service="http://host.docker.internal:$sqs_port"
fi
write_config() {
    cat >"$2" <<CONFIG
connections:
  mongodb:
    uri: $1
  dynamodb:
    endpoint: $dynamo_service
    region: us-east-1
  sqs:
    endpoint: $sqs_service
    queueUrl: $sqs_service$queue_path
    region: us-east-1
execution:
  network: $network
  recordsPerPartition: 10
  timeoutSeconds: 300
  workers:
    count: $workers
CONFIG
}
write_config "$mongo_uri" "$directory/bridge.yaml"
printf '[bridge-cli-test]\naws_access_key_id=dummy\naws_secret_access_key=dummy\n' >"$directory/test-credentials"
printf '[profile bridge-cli-test]\nregion=us-east-1\n' >"$directory/test-aws-config"

echo "3. Ejecutar la CLI real con $workers workers y las imágenes :ci"
brg run "$directory/migration.brg" --config "$directory/bridge.yaml" \
    --logs-dir "$directory/runtime-logs" 2>&1 | tee "$directory/cli.log"
local_aws "$dynamo_endpoint" dynamodb scan --table-name BridgeCliTest --consistent-read >"$directory/destination.json"

# Node ya es necesario para la CLI. Sus assertions permiten leer los JSON y los
# eventos sin una cadena complicada de expresiones Bash/jq.
node - "$directory" "$workers" <<'JS' | tee "$directory/evidence.json"
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const [root, workers] = process.argv.slice(2);
const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const runs = path.join(root, 'home/.brg/runs');
const ids = fs.readdirSync(runs);
assert.equal(ids.length, 1, 'Debe existir una sola ejecución');
const executionId = ids[0];
const manifest = readJson(path.join(runs, executionId, 'run.json'));
assert.equal(manifest.state, 'COMPLETED');
assert.equal(manifest.containers.filter(name => name.includes('-worker-')).length, Number(workers));
assert.equal(manifest.published, 11, '105 documentos / 10 por partición = 11 WorkUnits');
assert.equal(manifest.acknowledged, 11);
const plan = readJson(path.join(root, 'migration.plan.json'));
assert.equal(plan.planVersion, 1);
assert.equal(plan.bridgeName, 'CliSmoke');
const items = readJson(path.join(root, 'destination.json')).Items;
assert.equal(items.length, 105);
assert.equal(new Set(items.map(item => item.id.S)).size, 105);
for (let i = 1; i <= 105; i++) {
    const item = items.find(item => item.id.S === `item-${i}`);
    assert.ok(item, `Falta item-${i}`);
    assert.deepEqual(Object.keys(item).sort(), ['id', 'name', 'price', 'stock']);
    assert.deepEqual(item.name, {S: `Product ${i}`});
    assert.equal(Number(item.price.N), 12.5);
    assert.deepEqual(Object.keys(item.price), ['N']);
    assert.deepEqual(item.stock, {N: '3'});
}
const events = file => fs.readFileSync(file, 'utf8').split('\n')
    .filter(line => line.startsWith('BRIDGE_EVENT ')).map(line => JSON.parse(line.slice(13)));
const published = events(path.join(manifest.logsDirectory, `orchestrator-${executionId}-orchestrator.log`));
assert.ok(published.some(event => event.type === 'PUBLISHED' && event.publishedWorkUnits === 11));
assert.ok(!published.some(event => event.type === 'FAILED'));
const units = new Map();
const workersWithAcks = {};
for (const worker of manifest.containers.filter(name => name.includes('-worker-'))) {
    const observed = events(path.join(manifest.logsDirectory, `worker-${worker}.log`));
    assert.ok(observed.some(event => event.type === 'READY'));
    for (const event of observed.filter(event => event.type === 'ACKNOWLEDGED')) {
        assert.equal(event.migrationId, manifest.migrationId);
        assert.ok(Number.isInteger(event.workUnitId) && event.workUnitId >= 0 && event.workUnitId < 11);
        assert.ok(observed.some(start => start.type === 'PROCESSING' && start.workUnitId === event.workUnitId && start.migrationId === event.migrationId));
        assert.equal(event.recordsRead, event.recordsWritten);
        units.set(event.workUnitId, event.recordsWritten);
        workersWithAcks[worker] = (workersWithAcks[worker] || 0) + 1;
    }
}
assert.equal(units.size, 11, 'Deben confirmarse todas las WorkUnits distintas');
assert.equal([...units.values()].reduce((sum, count) => sum + count, 0), 105);
console.log(JSON.stringify({executionId, documents: 105, acknowledgedWorkUnits: units.size, workersWithAcks}, null, 2));
JS

echo '4. Comprobar limpieza de contenedores y fallo controlado'
if [[ $check_failure == true ]]; then
    write_config 'mongodb://host.docker.internal:1/?serverSelectionTimeoutMS=1000&connectTimeoutMS=1000' "$directory/bad-connection.yaml"
    if brg run "$directory/migration.brg" --config "$directory/bad-connection.yaml" \
        --logs-dir "$directory/runtime-logs" 2>&1 | tee "$directory/connection-failure.log"; then
        die 'La conexión incorrecta devolvió éxito'
    fi
    grep -q '^FAILED ' "$directory/connection-failure.log" || die 'Falta el diagnóstico FAILED'
    if grep -q '^COMPLETED ' "$directory/connection-failure.log"; then die 'El fallo produjo COMPLETED'; fi
    node - "$directory" <<'JS'
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
const runs = path.join(root, 'home/.brg/runs');
const manifests = fs.readdirSync(runs).map(id => JSON.parse(fs.readFileSync(path.join(runs, id, 'run.json'), 'utf8')));
assert.equal(manifests.length, 2);
const failed = manifests.filter(run => run.state === 'FAILED');
assert.equal(failed.length, 1);
const logs = fs.readdirSync(failed[0].logsDirectory).map(file => fs.readFileSync(path.join(failed[0].logsDirectory, file), 'utf8')).join('\n');
assert.ok(logs.includes('"type":"FAILED"'), 'El runtime debe registrar el fallo');
JS
fi
for manifest in "$directory/home/.brg/runs"/brg-*/run.json; do
    id=$(jq -er '.id' "$manifest")
    remaining=$(docker ps -a --filter "label=dev.nosqlbridge.run=$id" --format '{{.Names}}')
    [[ -z $remaining ]] || die "Contenedores residuales de $id: $remaining"
done
# La CLI no debe haber borrado ni los servicios ni los datos de la prueba.
for service in mongodb dynamodb-local elasticmq; do
    container=$(compose ps -q "$service")
    [[ -n $container ]] || die "La CLI ha eliminado el servicio $service"
    docker inspect "$container" | jq -e '.[0].State.Running == true' >/dev/null
done
compose exec -T mongodb mongosh --quiet --eval "if (db.getSiblingDB('bridge_cli_test').products.countDocuments({}) !== 105) quit(1)"
local_aws "$dynamo_endpoint" dynamodb scan --table-name BridgeCliTest --consistent-read >"$directory/after-failure.json"
node - "$directory" <<'JS'
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
const items = file => JSON.parse(fs.readFileSync(path.join(root, file), 'utf8')).Items.sort((a, b) => a.id.S.localeCompare(b.id.S));
assert.deepEqual(items('after-failure.json'), items('destination.json'), 'El fallo no debe cambiar los datos previos');
JS
local_aws "$sqs_endpoint" sqs get-queue-attributes --queue-url "$sqs_endpoint$queue_path" --attribute-names QueueArn >/dev/null
