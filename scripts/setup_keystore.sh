#!/bin/bash
set -e

# Path to the keystore
KEYSTORE_PATH="app/keystore.jks"
PKCS12_PATH="app/keystore.p12"

# Check if necessary environment variables are set
if [[ -z "$KEYSTORE_PRIVATE" || -z "$KEYSTORE_CHAIN" || -z "$KEYSTORE_PASSWORD" || -z "$KEY_ALIAS" ]]; then
    echo "Warning: Keystore secrets (PRIVATE, CHAIN, PASSWORD, ALIAS) are missing. Skipping keystore generation."
    exit 0
fi

echo "Generating keystore from secrets..."

# Write private key and chain to temporary files
echo "$KEYSTORE_PRIVATE" > private.key
echo "$KEYSTORE_CHAIN" > chain.crt

# Create PKCS12 keystore
# Note: We use KEYSTORE_PASSWORD for the export password
openssl pkcs12 -export -in chain.crt -inkey private.key -out "$PKCS12_PATH" \
    -name "$KEY_ALIAS" -passout pass:"$KEYSTORE_PASSWORD"

# Convert PKCS12 to JKS
# We use KEYSTORE_PASSWORD for destination storepass and KEY_PASSWORD for keypass
# If KEY_PASSWORD is not set, default to KEYSTORE_PASSWORD
K_PASS="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

# Remove existing JKS if it exists to avoid errors
rm -f "$KEYSTORE_PATH"

keytool -importkeystore \
    -srckeystore "$PKCS12_PATH" -srcstoretype PKCS12 -srcstorepass "$KEYSTORE_PASSWORD" \
    -destkeystore "$KEYSTORE_PATH" -deststoretype JKS -deststorepass "$KEYSTORE_PASSWORD" \
    -destkeypass "$K_PASS" \
    -noprompt

# Cleanup
rm private.key chain.crt "$PKCS12_PATH"

echo "Keystore generated at $KEYSTORE_PATH"
