#!/usr/bin/env bash
set -euo pipefail
# PKCS12 (keytool default): store and key password must be identical.
# Run locally once; store the keystore in a secret store (never commit).
keytool -genkeypair -v \
  -keystore dshmobile-release.keystore \
  -alias dshmobile \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "${DSHMOBILE_STORE_PASS:?set DSHMOBILE_STORE_PASS}" \
  -keypass "${DSHMOBILE_STORE_PASS}" \
  -dname "CN=dsh-mobile, OU=dsh-mobile, O=dsh-mobile, L=, S=, C="
