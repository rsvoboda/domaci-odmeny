#!/usr/bin/env bash
#
# Creates or updates the optional $1 monthly AWS Budget with email alerts.
#
# The budget is deliberately not part of the SAM stack: AWS::Budgets::Budget
# is not a supported CloudFormation resource type in eu-north-1, and the stack
# must stay deployable there. This script uses the Budgets API directly, which
# works in the deployment region.
#
# The script is idempotent: it creates the budget when missing and otherwise
# updates the limit and the two notifications (80% actual, 100% forecasted).
#
# Usage:
#   ./scripts/create-budget.sh --email you@example.com
#
set -euo pipefail

REGION="${AWS_REGION:-eu-north-1}"
BUDGET_NAME="${BUDGET_NAME:-domaci-odmeny-monthly}"
BUDGET_AMOUNT="${BUDGET_AMOUNT:-1}"
EMAIL=""
ACCOUNT_ID=""

usage() {
    cat <<'EOF'
Usage: create-budget.sh --email ADDRESS [options]

Options:
  --email ADDRESS      Email address for the budget alerts (required)
  --amount USD         Monthly budget limit (default: 1)
  --name NAME          Budget name (default: domaci-odmeny-monthly)
  --region REGION      AWS region (default: eu-north-1)
  --account-id ID      AWS account id (default: the current caller's account)
  -h, --help           Show this help

The budget sends an email when actual spend crosses 80% and when the
forecasted month-end spend crosses 100% of the limit.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --email)
            EMAIL="$2"
            shift 2
            ;;
        --amount)
            BUDGET_AMOUNT="$2"
            shift 2
            ;;
        --name)
            BUDGET_NAME="$2"
            shift 2
            ;;
        --region)
            REGION="$2"
            shift 2
            ;;
        --account-id)
            ACCOUNT_ID="$2"
            shift 2
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

command -v aws >/dev/null 2>&1 || {
    echo "aws CLI is required" >&2
    exit 1
}
command -v jq >/dev/null 2>&1 || {
    echo "jq is required" >&2
    exit 1
}

if [ -z "$EMAIL" ]; then
    echo "--email is required" >&2
    usage >&2
    exit 2
fi

if [ -z "$ACCOUNT_ID" ]; then
    ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
fi

budget_json="$(jq -n \
    --arg name "$BUDGET_NAME" \
    --arg amount "$BUDGET_AMOUNT" \
    '{BudgetName: $name, BudgetType: "COST", TimeUnit: "MONTHLY",
      BudgetLimit: {Amount: $amount, Unit: "USD"}}')"

notification_json() {
    local type="$1"
    local threshold="$2"
    jq -n \
        --arg type "$type" \
        --arg threshold "$threshold" \
        --arg email "$EMAIL" \
        '{Notification: {NotificationType: $type,
                        ComparisonOperator: "GREATER_THAN",
                        Threshold: ($threshold | tonumber)},
          Subscribers: [{SubscriptionType: "EMAIL", Address: $email}]}'
}

existing_notifications=""
if aws budgets describe-budget \
    --account-id "$ACCOUNT_ID" \
    --budget-name "$BUDGET_NAME" \
    --region "$REGION" >/dev/null 2>&1; then
    echo "Budget '$BUDGET_NAME' exists; updating it."
    aws budgets update-budget \
        --account-id "$ACCOUNT_ID" \
        --new-budget "$budget_json" \
        --region "$REGION" >/dev/null
    existing_notifications="$(aws budgets describe-notifications \
        --account-id "$ACCOUNT_ID" \
        --budget-name "$BUDGET_NAME" \
        --region "$REGION" \
        --query "Notifications[].NotificationType" \
        --output text)"
else
    echo "Creating budget '$BUDGET_NAME' with a \$$BUDGET_AMOUNT monthly limit."
    aws budgets create-budget \
        --account-id "$ACCOUNT_ID" \
        --budget "$budget_json" \
        --region "$REGION" >/dev/null
fi

upsert_notification() {
    local type="$1"
    local threshold="$2"
    local payload
    payload="$(notification_json "$type" "$threshold")"

    if printf '%s' "$existing_notifications" | grep -qw "$type"; then
        aws budgets update-notification \
            --account-id "$ACCOUNT_ID" \
            --budget-name "$BUDGET_NAME" \
            --notification "$(echo "$payload" | jq -c '.Notification')" \
            --subscribers "$(echo "$payload" | jq -c '.Subscribers')" \
            --region "$REGION" >/dev/null
    else
        aws budgets create-notification \
            --account-id "$ACCOUNT_ID" \
            --budget-name "$BUDGET_NAME" \
            --notification "$(echo "$payload" | jq -c '.Notification')" \
            --subscribers "$(echo "$payload" | jq -c '.Subscribers')" \
            --region "$REGION" >/dev/null
    fi
}

upsert_notification ACTUAL 80
upsert_notification FORECASTED 100

echo "Budget '$BUDGET_NAME' is active: \$$BUDGET_AMOUNT/month."
echo "Alerts go to $EMAIL at 80% actual and 100% forecasted spend."
echo "AWS sends a subscription confirmation email; confirm it to receive alerts."
