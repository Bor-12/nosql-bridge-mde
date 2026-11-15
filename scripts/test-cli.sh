#!/usr/bin/env bash
set -euo pipefail

worker_count=3
worker_image=${BRG_TEST_WORKER_IMAGE:-bridge-worker:ci}
orchestrator_image=${BRG_TEST_ORCHESTRATOR_IMAGE:-bridge-orchestrator:ci}
check_failure=false
via_host=false
use_profile=false

die() {
    echo "ERROR: $*" >&2
    exit 1
}

show_help() {
    echo 'Uso: bash scripts/test-cli.sh [--workers 1..8] [--check-failure] [--via-host] [--use-profile]'
    echo 'Requiere Linux/WSL, Node >=22.12, Docker Compose >=2.24.4, AWS CLI y jq.'
    echo 'Construir antes la CLI y las imágenes bridge-worker:ci y bridge-orchestrator:ci.'
    echo 'Release puede seleccionar la CLI y las imágenes mediante BRG_TEST_CLI_PATH, BRG_TEST_WORKER_IMAGE y BRG_TEST_ORCHESTRATOR_IMAGE.'
    echo 'Prueba 105 documentos, particiones de 10 y recursos Compose aislados. No borra imágenes.'
}

parse_arguments() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --workers)
                if [[ $# -lt 2 ]]; then
                    die '--workers necesita un valor de 1 a 8'
                fi
                case "$2" in
                    [1-8])
                        worker_count=$2
                        ;;
                    *)
                        die '--workers necesita un valor de 1 a 8'
                        ;;
                esac
                shift 2
                ;;
            --check-failure)
                check_failure=true
                shift
                ;;
            --via-host)
                via_host=true
                shift
                ;;
            --use-profile)
                use_profile=true
                shift
                ;;
            -h|--help)
                show_help
                exit 0
                ;;
            *)
                die "Opción desconocida: $1"
                ;;
        esac
    done
}

check_dependencies() {
    local tool
    for tool in node docker aws jq; do
        if ! command -v "$tool" >/dev/null; then
            die "Falta $tool dentro de Linux/WSL"
        fi
    done

    local script_directory
    script_directory=$(dirname -- "${BASH_SOURCE[0]}")
    repository_root=$(
        if ! cd -- "$script_directory/.."; then
            exit 1
        fi
        pwd
    )
    cli_path=${BRG_TEST_CLI_PATH:-"$repository_root/bridge-dsl/packages/cli/bin/cli.js"}
    if [[ ! -f "$cli_path" ]]; then
        die "CLI no encontrada: $cli_path"
    fi
    if [[ ! -f "$repository_root/bridge-dsl/packages/cli/dist/cli.js" ]]; then
        die 'Compila primero la CLI en bridge-dsl/'
    fi
    if ! docker info >/dev/null; then
        die 'Docker no está disponible'
    fi
    if ! docker image inspect "$worker_image" "$orchestrator_image" >/dev/null; then
        die "Imágenes no disponibles: $worker_image y $orchestrator_image"
    fi
}

prepare_test_environment() {
    # Cada prueba tiene sus propios servicios, estado de brg y diagnósticos.
    umask 077
    project_name="brg-e2e-$(node -e 'process.stdout.write(require("node:crypto").randomUUID())')"
    results_directory="$repository_root/scripts/test-results/$project_name"
    mkdir -p "$results_directory/home"

    # Docker conserva su configuración aunque brg utilice un HOME aislado.
    export DOCKER_CONFIG="${DOCKER_CONFIG:-$HOME/.docker}"
    export AWS_ACCESS_KEY_ID=dummy
    export AWS_SECRET_ACCESS_KEY=dummy
    export AWS_EC2_METADATA_DISABLED=true
    unset AWS_SESSION_TOKEN AWS_PROFILE

    # Puertos libres y sin volúmenes de datos del entorno de desarrollo.
    cat >"$results_directory/compose.override.yaml" <<'COMPOSE'
services:
  mongodb:
    ports: !override ["0:27017"]
    volumes: !override []
  dynamodb-local:
    ports: !override ["0:8000"]
    volumes: !override []
    command: ["-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb"]
  elasticmq:
    ports: !override ["0:9324"]
volumes: !reset {}
COMPOSE

    trap cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
}

compose() {
    docker compose --project-directory "$repository_root" -p "$project_name" \
        -f "$repository_root/docker-compose.yaml" \
        -f "$results_directory/compose.override.yaml" "$@"
}

# El subshell evita cambiar el HOME y las credenciales de los demás comandos.
brg() (
    export HOME="$results_directory/home"
    export BRG_TEST_WORKER_IMAGE="$worker_image"
    export BRG_TEST_ORCHESTRATOR_IMAGE="$orchestrator_image"
    if [[ $use_profile == true ]]; then
        unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
        export AWS_PROFILE=bridge-cli-test
        export AWS_SHARED_CREDENTIALS_FILE="$results_directory/test-credentials"
        export AWS_CONFIG_FILE="$results_directory/test-aws-config"
    fi
    node "$cli_path" "$@"
)

local_aws() {
    local endpoint=$1
    shift
    if [[ ! $endpoint =~ ^http://localhost:[0-9]+$ ]]; then
        die 'El test solo permite endpoints AWS locales'
    fi
    aws --endpoint-url "$endpoint" --region us-east-1 --no-cli-pager \
        --cli-connect-timeout 3 --cli-read-timeout 5 --output json "$@"
}

wait_until_ready() {
    local service_name=$1
    local deadline=$((SECONDS + 90))
    shift
    while true; do
        if "$@" >"$results_directory/readiness.log" 2>&1; then
            return 0
        fi
        if ((SECONDS >= deadline)); then
            cat "$results_directory/readiness.log" >&2
            die "Servicio no disponible: $service_name"
        fi
        sleep 2
    done
}

host_port() {
    local service_name=$1
    local container_port=$2
    local container_id
    container_id=$(compose ps -q "$service_name")
    if [[ -z $container_id ]]; then
        die "No se encuentra el servicio $service_name"
    fi
    docker inspect "$container_id" | jq -er --arg port "$container_port" \
        '.[0].NetworkSettings.Ports[$port][0].HostPort'
}

start_test_services() {
    echo '1. Preparar emuladores aislados'
    compose config --format json >"$results_directory/compose.json"
    # No iniciar servicios si Compose vuelve a incluir volúmenes de desarrollo.
    if ! jq -e '(.volumes // {} | length) == 0 and
        ([.services.mongodb.volumes[]?, .services["dynamodb-local"].volumes[]?] | length) == 0' \
        "$results_directory/compose.json" >/dev/null; then
        die 'El test no debe montar volúmenes de desarrollo'
    fi

    docker image inspect "$worker_image" "$orchestrator_image" >"$results_directory/images.json"
    compose up -d mongodb dynamodb-local elasticmq
    network_name=$(jq -er '.networks.default.name' "$results_directory/compose.json")
    mongo_host_port=$(host_port mongodb 27017/tcp)
    dynamo_host_port=$(host_port dynamodb-local 8000/tcp)
    sqs_host_port=$(host_port elasticmq 9324/tcp)
    dynamo_endpoint="http://localhost:$dynamo_host_port"
    sqs_endpoint="http://localhost:$sqs_host_port"

    wait_until_ready MongoDB compose exec -T mongodb mongosh --quiet --eval 'if (db.adminCommand({ping:1}).ok !== 1) quit(1)'
    wait_until_ready 'DynamoDB Local' local_aws "$dynamo_endpoint" dynamodb list-tables
    wait_until_ready ElasticMQ local_aws "$sqs_endpoint" sqs list-queues
}

write_config() {
    local mongo_connection_uri=$1
    local config_file=$2
    cat >"$config_file" <<CONFIG
connections:
  mongodb:
    uri: $mongo_connection_uri
  dynamodb:
    endpoint: $dynamo_service
    region: us-east-1
  sqs:
    endpoint: $sqs_service
    queueUrl: $sqs_service$queue_path
    region: us-east-1
execution:
  network: $network_name
  recordsPerPartition: 10
  timeoutSeconds: 300
  workers:
    count: $worker_count
CONFIG
}

prepare_migration_files() {
    cat >"$results_directory/migration.brg" <<'MODEL'
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
        network_name=bridge
        mongo_uri="mongodb://host.docker.internal:$mongo_host_port"
        dynamo_service="http://host.docker.internal:$dynamo_host_port"
        sqs_service="http://host.docker.internal:$sqs_host_port"
    fi
    write_config "$mongo_uri" "$results_directory/bridge.yaml"

    # Perfil ficticio para probar el montaje de credenciales, sin acceder a AWS.
    cat >"$results_directory/test-credentials" <<'CREDENTIALS'
[bridge-cli-test]
aws_access_key_id=dummy
aws_secret_access_key=dummy
CREDENTIALS
    cat >"$results_directory/test-aws-config" <<'AWS_CONFIG'
[profile bridge-cli-test]
region=us-east-1
AWS_CONFIG
}

create_test_data() {
    echo '2. Preparar 105 documentos, una tabla vacía y la cola'
    local_aws "$dynamo_endpoint" dynamodb create-table --table-name BridgeCliTest \
        --attribute-definitions AttributeName=id,AttributeType=S \
        --key-schema AttributeName=id,KeyType=HASH --billing-mode PAY_PER_REQUEST >/dev/null
    local_aws "$dynamo_endpoint" dynamodb wait table-exists --table-name BridgeCliTest
    local queue_url
    queue_url=$(local_aws "$sqs_endpoint" sqs create-queue --queue-name migration-work | jq -er '.QueueUrl')
    queue_path="/$(echo "$queue_url" | cut -d '/' -f 4-)"
    compose exec -T mongodb mongosh --quiet --eval "
        db.getSiblingDB('bridge_cli_test').products.insertMany(
            Array.from({length: 105}, (_, i) => ({
                _id: 'item-' + (i + 1), name: 'Product ' + (i + 1), price: '12.50', stock: '3'
            }))
        )" >/dev/null
    prepare_migration_files
}

run_migration() {
    echo "3. Ejecutar la CLI real con $worker_count workers: $worker_image y $orchestrator_image"
    brg run "$results_directory/migration.brg" --config "$results_directory/bridge.yaml" \
        --logs-dir "$results_directory/runtime-logs" 2>&1 | tee "$results_directory/cli.log"
}

verify_migration() {
    local_aws "$dynamo_endpoint" dynamodb scan --table-name BridgeCliTest --consistent-read >"$results_directory/destination.json"
    # Node permite comprobar documentos, tipos y eventos sin complicar Bash.
    node - "$results_directory" "$worker_count" <<'JS' | tee "$results_directory/evidence.json"
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
}

run_failure_test() {
    echo '4. Comprobar fallo controlado'
    write_config 'mongodb://host.docker.internal:1/?serverSelectionTimeoutMS=1000&connectTimeoutMS=1000' "$results_directory/bad-connection.yaml"
    if brg run "$results_directory/migration.brg" --config "$results_directory/bad-connection.yaml" \
        --logs-dir "$results_directory/runtime-logs" 2>&1 | tee "$results_directory/connection-failure.log"; then
        die 'La conexión incorrecta devolvió éxito'
    fi
    if ! grep -q '^FAILED ' "$results_directory/connection-failure.log"; then
        die 'Falta el diagnóstico FAILED'
    fi
    if grep -q '^COMPLETED ' "$results_directory/connection-failure.log"; then
        die 'El fallo produjo COMPLETED'
    fi
    node - "$results_directory" <<'JS'
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
}

verify_resources() {
    echo '5. Comprobar limpieza y conservación de los datos'
    local manifest_file
    local execution_id
    local remaining_containers
    for manifest_file in "$results_directory/home/.brg/runs"/brg-*/run.json; do
        execution_id=$(jq -er '.id' "$manifest_file")
        remaining_containers=$(docker ps -a --filter "label=dev.nosqlbridge.run=$execution_id" --format '{{.Names}}')
        if [[ -n $remaining_containers ]]; then
            die "Contenedores residuales de $execution_id: $remaining_containers"
        fi
    done

    local service_name
    local container_id
    for service_name in mongodb dynamodb-local elasticmq; do
        container_id=$(compose ps -q "$service_name")
        if [[ -z $container_id ]]; then
            die "La CLI ha eliminado el servicio $service_name"
        fi
        docker inspect "$container_id" | jq -e '.[0].State.Running == true' >/dev/null
    done
    compose exec -T mongodb mongosh --quiet --eval "if (db.getSiblingDB('bridge_cli_test').products.countDocuments({}) !== 105) quit(1)"
    local_aws "$dynamo_endpoint" dynamodb scan --table-name BridgeCliTest --consistent-read >"$results_directory/after-failure.json"
    node - "$results_directory" <<'JS'
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
const items = file => JSON.parse(fs.readFileSync(path.join(root, file), 'utf8')).Items.sort((a, b) => a.id.S.localeCompare(b.id.S));
assert.deepEqual(items('after-failure.json'), items('destination.json'), 'El fallo no debe cambiar los datos previos');
JS
    local_aws "$sqs_endpoint" sqs get-queue-attributes --queue-url "$sqs_endpoint$queue_path" --attribute-names QueueArn >/dev/null
}

cleanup_cli_containers() {
    local cleanup_failed=false
    local manifest_file
    local execution_id
    for manifest_file in "$results_directory/home/.brg/runs"/brg-*/run.json; do
        if [[ ! -f $manifest_file ]]; then
            continue
        fi
        if ! execution_id=$(jq -er '.id' "$manifest_file"); then
            cleanup_failed=true
            continue
        fi
        if ! brg cleanup "$execution_id" >>"$results_directory/cleanup.log" 2>&1; then
            cleanup_failed=true
        fi
    done
    if [[ $cleanup_failed == true ]]; then
        return 1
    fi
}

print_failure_logs() {
    echo 'ERROR: logs de los servicios de prueba:' >&2
    tail -n 100 "$results_directory/services.log" >&2
    echo 'ERROR: E2E fallido. Diagnósticos de limpieza:' >&2
    cat "$results_directory/compose-cleanup.log" >&2
    local log_file
    for log_file in "$results_directory/runtime-logs"/brg-*/*.log; do
        if [[ -f $log_file ]]; then
            echo "--- $log_file ---" >&2
            tail -n 100 "$log_file" >&2
        fi
    done
}

cleanup() {
    # Guardar el resultado antes de limpiar y evitar volver a ejecutar el trap.
    local exit_status=$?
    local cleanup_failed=false
    trap - EXIT
    if ! compose logs --no-color >"$results_directory/services.log" 2>&1; then
        echo 'No se pudieron obtener todos los logs de Compose.' >&2
    fi
    if ! cleanup_cli_containers; then
        cleanup_failed=true
    fi
    # Solo se elimina el proyecto único de esta prueba, nunca las imágenes.
    if ! compose down --volumes --remove-orphans >"$results_directory/compose-cleanup.log" 2>&1; then
        cleanup_failed=true
    fi
    if [[ $exit_status == 0 ]]; then
        if [[ $cleanup_failed == true ]]; then
            exit_status=1
        fi
    fi
    if [[ $exit_status != 0 ]]; then
        if ! print_failure_logs; then
            echo 'No se pudieron mostrar todos los diagnósticos.' >&2
        fi
    fi
    echo "Diagnósticos conservados en: $results_directory"
    if [[ $exit_status == 0 ]]; then
        echo "PASS: 105 documentos, 11 WorkUnits, $worker_count workers configurados; ver evidence.json para saber cuáles procesaron trabajo."
    fi
    exit "$exit_status"
}

main() {
    parse_arguments "$@"
    check_dependencies
    prepare_test_environment
    start_test_services
    create_test_data
    run_migration
    verify_migration
    if [[ $check_failure == true ]]; then
        run_failure_test
    fi
    verify_resources
    # El trap EXIT limpia también cuando una etapa falla o se interrumpe.
}

main "$@"
