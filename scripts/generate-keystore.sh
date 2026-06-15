#!/usr/bin/env bash
# Generates a release signing keystore for the app.
# Run ONCE. Keep keystore/release.jks and keystore.properties out of version control.
#
# Usage (from project root):
#   ./scripts/generate-keystore.sh

set -euo pipefail

KEYSTORE_PATH="keystore/release.jks"
KEY_ALIAS="steamcontroller"
VALIDITY_DAYS=$((25 * 365))

if [[ -f "$KEYSTORE_PATH" ]]; then
    echo "ERROR: Keystore already exists at $KEYSTORE_PATH" >&2
    echo "Delete it manually if you really want to regenerate (this invalidates previously signed APKs)." >&2
    exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
    echo "ERROR: keytool not found. Install a JDK (Android Studio bundles one in jbr/) and add it to PATH." >&2
    exit 1
fi

mkdir -p "$(dirname "$KEYSTORE_PATH")"

cat <<EOF

=== Release keystore generation ===
You'll be prompted for two passwords (use the SAME for both — simpler) and identity fields.
REMEMBER these passwords. Losing them means losing the ability to update your app.

EOF

keytool -genkey -v \
    -keystore "$KEYSTORE_PATH" \
    -keyalg RSA \
    -keysize 4096 \
    -validity "$VALIDITY_DAYS" \
    -alias "$KEY_ALIAS"

cat <<EOF

Keystore created at $KEYSTORE_PATH

Next step: create keystore.properties at the project root with:

storeFile=$KEYSTORE_PATH
storePassword=<your-password>
keyAlias=$KEY_ALIAS
keyPassword=<your-password>

Both keystore.properties and keystore/ are gitignored — they MUST stay local.
EOF
