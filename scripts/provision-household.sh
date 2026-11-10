#!/usr/bin/env bash
#
# Provisions the Cognito users and the household/member records for a deployed
# Domácí odměny stack.
#
# The script is idempotent:
#   * a Cognito user is created only when it does not exist yet;
#   * a permanent password is generated only for a newly created user;
#   * an existing member item keeps its display name, allowance and active flag,
#     so names edited in the app are never overwritten by a re-run;
#   * the household record is created only when missing.
#
# Prerequisites: AWS CLI v2, jq, openssl, and a valid AWS session with permission
# to manage Cognito users and write to the stack's Members table.
#
# Usage:
#   ./scripts/provision-household.sh \
#     --stack-name domaci-odmeny \
#     --region eu-north-1 \
#     --members-file scripts/members.example.tsv
#
set -euo pipefail

STACK_NAME="${STACK_NAME:-domaci-odmeny}"
REGION="${AWS_REGION:-eu-north-1}"
MEMBERS_FILE=""
HOUSEHOLD_ID="${HOUSEHOLD_ID:-default}"
HOUSEHOLD_NAME=""
CREATE_PASSWORDS="true"

usage() {
    cat <<'EOF'
Usage: provision-household.sh [options]

Options:
  --stack-name NAME       CloudFormation stack name (default: domaci-odmeny)
  --region REGION         AWS region (default: eu-north-1)
  --members-file FILE     Tab-separated member definitions
                          (default: scripts/members.example.tsv)
  --household-id ID       Household id (default: default)
  --household-name NAME   Name used only when the household record is created
                          (default: Naše domácnost)
  --no-passwords          Do not generate passwords for newly created users.
                          They stay in FORCE_CHANGE_PASSWORD until an
                          administrator sets a permanent password.
  -h, --help              Show this help

The members file has five columns separated by tabs (two or more spaces
between columns are accepted too):
  member-id  cognito-username  display-name  PARENT|CHILD  base-allowance-CZK

Lines starting with # and blank lines are ignored.
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
        --members-file)
            MEMBERS_FILE="$2"
            shift 2
            ;;
        --household-id)
            HOUSEHOLD_ID="$2"
            shift 2
            ;;
        --household-name)
            HOUSEHOLD_NAME="$2"
            shift 2
            ;;
        --no-passwords)
            CREATE_PASSWORDS="false"
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

command -v aws >/dev/null 2>&1 || {
    echo "aws CLI is required" >&2
    exit 1
}
command -v jq >/dev/null 2>&1 || {
    echo "jq is required" >&2
    exit 1
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "$MEMBERS_FILE" ]; then
    MEMBERS_FILE="$SCRIPT_DIR/members.example.tsv"
fi
if [ ! -f "$MEMBERS_FILE" ]; then
    echo "Members file not found: $MEMBERS_FILE" >&2
    exit 1
fi

echo "Reading stack outputs from $STACK_NAME ($REGION)..."
OUTPUTS_JSON="$(aws cloudformation describe-stacks \
    --stack-name "$STACK_NAME" \
    --region "$REGION" \
    --query "Stacks[0].Outputs" \
    --output json)"

output_value() {
    echo "$OUTPUTS_JSON" | jq -r --arg key "$1" \
        '.[] | select(.OutputKey == $key) | .OutputValue'
}

USER_POOL_ID="$(output_value CognitoUserPoolId)"
MEMBERS_TABLE="$(output_value MembersTable)"

for required in USER_POOL_ID MEMBERS_TABLE; do
    value="${!required}"
    if [ -z "$value" ] || [ "$value" = "null" ]; then
        echo "Missing stack output for $required. Is the stack deployed?" >&2
        exit 1
    fi
done

household_key="$(jq -n \
    --arg pk "HOUSEHOLD#$HOUSEHOLD_ID" \
    --arg sk "HOUSEHOLD#$HOUSEHOLD_ID" \
    '{PK: {S: $pk}, SK: {S: $sk}}')"

existing_household="$(aws dynamodb get-item \
    --table-name "$MEMBERS_TABLE" \
    --region "$REGION" \
    --key "$household_key" \
    --query "Item" \
    --output json)"

if [ -z "$existing_household" ] || [ "$existing_household" = "null" ]; then
    name="${HOUSEHOLD_NAME:-Naše domácnost}"
    household_item="$(jq -n \
        --arg pk "HOUSEHOLD#$HOUSEHOLD_ID" \
        --arg sk "HOUSEHOLD#$HOUSEHOLD_ID" \
        --arg id "$HOUSEHOLD_ID" \
        --arg name "$name" \
        '{PK: {S: $pk}, SK: {S: $sk}, entityType: {S: "HOUSEHOLD"},
          id: {S: $id}, name: {S: $name},
          currency: {S: "CZK"}, timezone: {S: "Europe/Prague"}}')"
    aws dynamodb put-item \
        --table-name "$MEMBERS_TABLE" \
        --region "$REGION" \
        --item "$household_item" \
        --condition-expression "attribute_not_exists(PK)"
    echo "Created household record '$HOUSEHOLD_ID' ($name)."
else
    echo "Household record '$HOUSEHOLD_ID' already exists; leaving it unchanged."
fi

NEW_ACCOUNTS=""
MEMBER_COUNT=0

# The documented format is tab-separated, but a file with two or more spaces
# between the columns is accepted as well. A final line without a trailing
# newline is processed, and CRLF line endings are tolerated.
while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    case "$line" in
        ""|\#*) continue ;;
    esac

    if [[ "$line" == *$'\t'* ]]; then
        IFS=$'\t' read -r member_id username display_name role base_czk <<< "$line"
    else
        normalized="$(printf '%s' "$line" | sed -E "s/[[:space:]]{2,}/$(printf '\t')/g")"
        IFS=$'\t' read -r member_id username display_name role base_czk <<< "$normalized"
    fi

    if [ -z "$username" ] || [ -z "$display_name" ] || [ -z "$role" ]; then
        echo "Skipping incomplete line for member '$member_id'." >&2
        continue
    fi
    case "$role" in
        PARENT|CHILD) ;;
        *)
            echo "Invalid role '$role' for '$member_id' (expected PARENT or CHILD)." >&2
            exit 1
            ;;
    esac
    base_czk="${base_czk:-0}"
    case "$base_czk" in
        ''|*[!0-9]*)
            echo "Base allowance for '$member_id' must be a whole number of CZK." >&2
            exit 1
            ;;
    esac
    base_czk=$((10#$base_czk))
    base_minor=$((base_czk * 100))

    created="false"
    if aws cognito-idp admin-get-user \
        --user-pool-id "$USER_POOL_ID" \
        --region "$REGION" \
        --username "$username" >/dev/null 2>&1; then
        aws cognito-idp admin-update-user-attributes \
            --user-pool-id "$USER_POOL_ID" \
            --region "$REGION" \
            --username "$username" \
            --user-attributes "Name=name,Value=$display_name" >/dev/null
    else
        aws cognito-idp admin-create-user \
            --user-pool-id "$USER_POOL_ID" \
            --region "$REGION" \
            --username "$username" \
            --user-attributes "Name=name,Value=$display_name" \
            --message-action SUPPRESS >/dev/null
        created="true"
    fi

    group="children"
    if [ "$role" = "PARENT" ]; then
        group="parents"
    fi
    aws cognito-idp admin-add-user-to-group \
        --user-pool-id "$USER_POOL_ID" \
        --region "$REGION" \
        --username "$username" \
        --group-name "$group"

    if [ "$created" = "true" ] && [ "$CREATE_PASSWORDS" = "true" ]; then
        password="$(openssl rand -hex 6)Aa1"
        aws cognito-idp admin-set-user-password \
            --user-pool-id "$USER_POOL_ID" \
            --region "$REGION" \
            --username "$username" \
            --password "$password" \
            --permanent
        NEW_ACCOUNTS="${NEW_ACCOUNTS}${username}\t${password}\n"
    fi

    sub="$(aws cognito-idp admin-get-user \
        --user-pool-id "$USER_POOL_ID" \
        --region "$REGION" \
        --username "$username" \
        --query "UserAttributes[?Name=='sub'].Value" \
        --output text)"
    if [ -z "$sub" ] || [ "$sub" = "None" ]; then
        echo "Could not read the Cognito sub for '$username'." >&2
        exit 1
    fi

    member_key="$(jq -n \
        --arg pk "HOUSEHOLD#$HOUSEHOLD_ID" \
        --arg sk "MEMBER#$member_id" \
        '{PK: {S: $pk}, SK: {S: $sk}}')"

    existing_member="$(aws dynamodb get-item \
        --table-name "$MEMBERS_TABLE" \
        --region "$REGION" \
        --key "$member_key" \
        --query "Item" \
        --output json)"

    if [ -z "$existing_member" ] || [ "$existing_member" = "null" ]; then
        member_item="$(jq -n \
            --arg pk "HOUSEHOLD#$HOUSEHOLD_ID" \
            --arg sk "MEMBER#$member_id" \
            --arg id "$member_id" \
            --arg household "$HOUSEHOLD_ID" \
            --arg sub "$sub" \
            --arg name "$display_name" \
            --arg role "$role" \
            --arg base "$base_minor" \
            '{PK: {S: $pk}, SK: {S: $sk}, entityType: {S: "MEMBER"},
              id: {S: $id}, householdId: {S: $household},
              cognitoSub: {S: $sub}, displayName: {S: $name},
              role: {S: $role}, baseAllowanceMinor: {N: $base},
              active: {BOOL: true}}')"
        aws dynamodb put-item \
            --table-name "$MEMBERS_TABLE" \
            --region "$REGION" \
            --item "$member_item"
        echo "Created member '$member_id' ($display_name, $role)."
    else
        update_values="$(jq -n \
            --arg sub "$sub" \
            --arg household "$HOUSEHOLD_ID" \
            --arg role "$role" \
            '{":sub": {S: $sub}, ":household": {S: $household}, ":role": {S: $role}}')"
        aws dynamodb update-item \
            --table-name "$MEMBERS_TABLE" \
            --region "$REGION" \
            --key "$member_key" \
            --update-expression "SET cognitoSub = :sub, householdId = :household, #role = :role" \
            --expression-attribute-names '{"#role": "role"}' \
            --expression-attribute-values "$update_values" >/dev/null
        echo "Updated the Cognito link for member '$member_id' (kept the app-managed name and allowance)."
    fi

    MEMBER_COUNT=$((MEMBER_COUNT + 1))
done < "$MEMBERS_FILE"

echo "Provisioned $MEMBER_COUNT member(s)."

if [ -n "$NEW_ACCOUNTS" ]; then
    echo
    echo "New accounts and one-time passwords (change them after the first sign-in):"
    printf "%b" "$NEW_ACCOUNTS"
else
    echo "No new Cognito accounts were created."
fi

echo
echo "The chore catalog is seeded by the application on its first start (SEED_CATALOG=true)."
echo "Open the FunctionUrl stack output and sign in with a parent account."
