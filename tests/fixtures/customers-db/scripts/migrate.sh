#!/bin/sh
set -eu

fail() { printf '%s\n' "customers-db-migrate: $1" >&2; exit 2; }
[ "$#" -le 1 ] || fail 'only one operation is accepted'
operation=${1:-migrate}
case "$operation" in migrate|validate|info) ;; *) fail 'operation must be migrate, validate or info' ;; esac
[ -n "${FLYWAY_USER:-}" ] || fail 'FLYWAY_USER is required'
[ -n "${FLYWAY_URL:-}" ] || fail 'FLYWAY_URL is required'
# Never echo an invalid URL: it might contain credentials. Reject multiline input.
if printf '%s' "$FLYWAY_URL" | LC_ALL=C grep -q '[[:space:]]'; then
    fail 'FLYWAY_URL must not contain whitespace'
fi
address=${FLYWAY_URL%%\?*}
printf '%s\n' "$address" | grep -Eq '^jdbc:postgresql://[a-zA-Z0-9._-]+:[0-9]{1,5}/[a-zA-Z0-9_-]+$' \
    || fail 'FLYWAY_URL must name host:port/database without credentials'
case "$FLYWAY_URL" in
    *\?*)
        query=${FLYWAY_URL#*\?}
        case "$query" in ''|\&*|*\&|*\&\&*) fail 'invalid JDBC property list' ;; esac
        seen=''
        saved_ifs=$IFS
        IFS='&'
        set -f
        for property in $query; do
            key=${property%%=*}
            value=${property#*=}
            case " $seen " in *" $key "*) fail 'duplicate JDBC property' ;; esac
            seen="$seen $key"
            case "$key" in
                connectTimeout) case "$value" in [1-5]) ;; *) fail 'connectTimeout must be 1..5 seconds' ;; esac ;;
                loginTimeout) case "$value" in [1-9]|10) ;; *) fail 'loginTimeout must be 1..10 seconds' ;; esac ;;
                socketTimeout) case "$value" in [1-9]|[1-5][0-9]|60) ;; *) fail 'socketTimeout must be 1..60 seconds' ;; esac ;;
                sslmode) case "$value" in disable|prefer|require|verify-ca|verify-full) ;; *) fail 'unsupported sslmode' ;; esac ;;
                *) fail 'unsupported JDBC property' ;;
            esac
        done
        IFS=$saved_ifs
        set +f
        ;;
esac
# Explicit file wins. An invalid file is an error, never a silent env fallback.
if [ -n "${FLYWAY_PASSWORD_FILE:-}" ]; then
    [ -f "$FLYWAY_PASSWORD_FILE" ] && [ -r "$FLYWAY_PASSWORD_FILE" ] \
        || fail 'administrative password file must exist and be readable'
    FLYWAY_PASSWORD=$(cat "$FLYWAY_PASSWORD_FILE")
fi
[ -n "${FLYWAY_PASSWORD:-}" ] || fail 'FLYWAY_PASSWORD or a nonempty password file is required'
export FLYWAY_PASSWORD

cd /workspace
# Fixed arguments cannot be overridden by callers. No debug/raw database logs:
# errors can contain credentials, JDBC parameters, SQL literals or customer data.
status=0
/flyway/flyway -configFiles=/workspace/flyway.toml "$operation" >/dev/null 2>&1 || status=$?
unset FLYWAY_PASSWORD
if [ "$status" -ne 0 ]; then
    printf '%s\n' 'customers-db-migrate: Flyway failed; verify connectivity, admin credentials, customers_app, legacy history and migration checksums in a protected administrative session.' >&2
    exit "$status"
fi
printf 'customers-db-migrate: %s completed successfully\n' "$operation"
