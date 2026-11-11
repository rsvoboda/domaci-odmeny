#!/usr/bin/env bash
#
# Build and deploy Domácí odměny to AWS in one step.
#
# The session secret and the Cognito callback URLs are the two parameters that
# are easy to forget and painful to get wrong: a wrong callback URL breaks
# sign-in, and a changed session secret signs everybody out. This script keeps
# both out of the repository and passes them correctly on every deploy.
#
# Usage:
#   ./scripts/deploy.sh                 # build, deploy, verify health
#   ./scripts/deploy.sh --skip-build    # deploy the existing target/function.zip
#
# The first deployment of a brand-new stack cannot know the Function URL yet.
# In that case the script deploys with the template's placeholder URLs and tells
# you to run it again; the second run picks up the real URL automatically.
#
set -euo pipefail

STACK_NAME="${STACK_NAME:-domaci-odmeny}"
REGION="${AWS_REGION:-eu-north-1}"
SECRET_FILE="${SECRET_FILE:-$HOME/.config/domaci-odmeny/session-secret}"
BASE_URL=""
SKIP_BUILD="false"
SKIP_VERIFY="false"

usage() {
    cat <<'EOF'
Usage: deploy.sh [options]

Options:
  --stack-name NAME             CloudFormation stack name (default: domaci-odmeny)
  --region REGION               AWS region (default: eu-north-1)
  --base-url URL                Public base URL of the app. Defaults to the
                                FunctionUrl output of the existing stack.
  --session-secret-file FILE    File with the OIDC session secret
                                (default: ~/.config/domaci-odmeny/session-secret)
  --skip-build                  Do not run ./mvnw package; deploy the current
                                target/function.zip
  --skip-verify                 Do not poll /q/health after the deploy
  -h, --help                    Show this help

The script never prints the session secret and refuses to deploy without one.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --stack-name)
            STACK_NAME="$2"
            shift 2
            ;;
        --region)
            REGION="$2"
            shift 2
            ;;
        --base-url)
            BASE_URL="$2"
            shift 2
            ;;
        --session-secret-file)
            SECRET_FILE="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD="true"
            shift
            ;;
        --skip-verify)
            SKIP_VERIFY="true"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

for tool in aws sam curl; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "$tool is required but was not found in PATH." >&2
        exit 1
    }
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_DIR"

# --------------------------------------------------------------------------
# Session secret
# --------------------------------------------------------------------------
if [ ! -s "$SECRET_FILE" ]; then
    echo "Session secret file not found or empty: $SECRET_FILE" >&2
    echo "Create one with:" >&2
    echo "  mkdir -p ~/.config/domaci-odmeny && chmod 700 ~/.config/domaci-odmeny" >&2
    echo "  openssl rand -base64 48 | tr -d '\\n' > ~/.config/domaci-odmeny/session-secret" >&2
    echo "  chmod 600 ~/.config/domaci-odmeny/session-secret" >&2
    exit 1
fi

SESSION_SECRET="$(tr -d '\r\n' < "$SECRET_FILE")"
if [ "${#SESSION_SECRET}" -lt 32 ]; then
    echo "The session secret in $SECRET_FILE is shorter than 32 characters." >&2
    exit 1
fi

if [ "$(uname -s)" = "Darwin" ] || [ "$(uname -s)" = "Linux" ]; then
    perms="$(stat -f '%Lp' "$SECRET_FILE" 2>/dev/null || stat -c '%a' "$SECRET_FILE" 2>/dev/null || echo '')"
    if [ -n "$perms" ] && [ "$perms" != "600" ]; then
        echo "Warning: $SECRET_FILE has mode $perms; 'chmod 600' it." >&2
    fi
fi

# --------------------------------------------------------------------------
# Preflight
# --------------------------------------------------------------------------
IDENTITY="$(aws sts get-caller-identity --query 'Arn' --output text 2>/dev/null || true)"
if [ -z "$IDENTITY" ]; then
    echo "No valid AWS session. Run 'aws login' (or configure credentials) first." >&2
    exit 1
fi
echo "AWS identity: $IDENTITY"

if git -C "$REPO_DIR" rev-parse --git-dir >/dev/null 2>&1; then
    if [ -n "$(git -C "$REPO_DIR" status --porcelain --untracked-files=no)" ]; then
        echo "Warning: the working tree has uncommitted changes; production will not match any commit." >&2
    fi
    echo "Commit: $(git -C "$REPO_DIR" rev-parse --short HEAD) ($(git -C "$REPO_DIR" rev-parse --abbrev-ref HEAD))"
fi

# --------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------
if [ "$SKIP_BUILD" = "true" ]; then
    if [ ! -f target/function.zip ]; then
        echo "--skip-build was given but target/function.zip does not exist." >&2
        exit 1
    fi
    echo "Skipping the build; deploying the existing target/function.zip."
else
    echo "Building target/function.zip (runs the test suite)..."
    ./mvnw package
fi

# --------------------------------------------------------------------------
# Public base URL
# --------------------------------------------------------------------------
if [ -z "$BASE_URL" ]; then
    BASE_URL="$(aws cloudformation describe-stacks \
        --stack-name "$STACK_NAME" \
        --region "$REGION" \
        --query "Stacks[0].Outputs[?OutputKey=='FunctionUrl'].OutputValue" \
        --output text 2>/dev/null || true)"
    if [ "$BASE_URL" = "None" ]; then
        BASE_URL=""
    fi
fi
BASE_URL="${BASE_URL%/}"

params=("SessionSecret=$SESSION_SECRET")
if [ -n "$BASE_URL" ]; then
    params+=("OidcCallbackUrl=$BASE_URL/q/oidc-callback" "OidcLogoutUrl=$BASE_URL/")
    echo "Public base URL: $BASE_URL"
else
    echo "No FunctionUrl found for stack '$STACK_NAME'; this looks like a first deploy."
    echo "Cognito will use the placeholder localhost URLs until the script is run again."
fi

# --------------------------------------------------------------------------
# Deploy
# --------------------------------------------------------------------------
sam deploy \
    --template-file sam/template.yaml \
    --stack-name "$STACK_NAME" \
    --region "$REGION" \
    --capabilities CAPABILITY_IAM \
    --resolve-s3 \
    --no-confirm-changeset \
    --no-fail-on-empty-changeset \
    --parameter-overrides "${params[@]}"

# A first deployment has no FunctionUrl until CloudFormation finishes.
if [ -z "$BASE_URL" ]; then
    BASE_URL="$(aws cloudformation describe-stacks \
        --stack-name "$STACK_NAME" \
        --region "$REGION" \
        --query "Stacks[0].Outputs[?OutputKey=='FunctionUrl'].OutputValue" \
        --output text 2>/dev/null || true)"
    BASE_URL="${BASE_URL%/}"
    if [ -n "$BASE_URL" ]; then
        echo
        echo "Stack created. Run this script again so Cognito gets the real callback URL:"
        echo "  ./scripts/deploy.sh --skip-build"
        exit 0
    fi
fi

# --------------------------------------------------------------------------
# Verify
# --------------------------------------------------------------------------
if [ "$SKIP_VERIFY" = "true" ] || [ -z "$BASE_URL" ]; then
    exit 0
fi

echo "Waiting for $BASE_URL/q/health ..."
for attempt in $(seq 1 12); do
    code="$(curl -sS --max-time 20 -o /tmp/domaci-odmeny-health.$$ -w '%{http_code}' "$BASE_URL/q/health" 2>/dev/null || true)"
    if [ "$code" = "200" ] && grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' /tmp/domaci-odmeny-health.$$ 2>/dev/null; then
        echo "Health check passed: $BASE_URL/q/health"
        rm -f /tmp/domaci-odmeny-health.$$
        exit 0
    fi
    echo "  attempt $attempt: HTTP ${code:-no response}"
    sleep 5
done

rm -f /tmp/domaci-odmeny-health.$$
echo "Health check did not pass. Inspect the stack events and CloudWatch logs:" >&2
echo "  aws cloudformation describe-stack-events --stack-name $STACK_NAME --region $REGION --max-items 10" >&2
echo "  aws logs tail /aws/lambda/$STACK_NAME-app --region $REGION --since 15m" >&2
exit 1
