#!/bin/bash
# Quick OAuth token generator for testing
# Usage: ./get-token.sh [scope]
# Example: ./get-token.sh "system/*.read"
# Default: Full CRUD access on all resources (system/*.read + system/*.write)

SCOPE="${1:-system/*.read system/*.write}"

echo "🔐 Getting OAuth access token with scope: $SCOPE"
echo ""

RESPONSE=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Authorization: Basic $(echo -n 'test-client:test-secret' | base64)" \
  -d "grant_type=client_credentials" \
  -d "scope=$SCOPE")

# Check if response contains access_token
if echo "$RESPONSE" | jq -e '.access_token' > /dev/null 2>&1; then
  ACCESS_TOKEN=$(echo "$RESPONSE" | jq -r '.access_token')
  EXPIRES_IN=$(echo "$RESPONSE" | jq -r '.expires_in')
  TOKEN_SCOPE=$(echo "$RESPONSE" | jq -r '.scope')
  
  echo "✅ Access Token Generated!"
  echo ""
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "ACCESS_TOKEN:"
  echo "$ACCESS_TOKEN"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo ""
  echo "📋 Token Info:"
  echo "   • Scope: $TOKEN_SCOPE"
  echo "   • Expires in: $EXPIRES_IN seconds ($((EXPIRES_IN / 60)) minutes)"
  echo ""
  echo "🧪 Test with curl:"
  echo "   curl -H \"Authorization: Bearer $ACCESS_TOKEN\" http://localhost:8080/fhir/Patient"
  echo ""
  echo "📝 Export as environment variable:"
  echo "   export ACCESS_TOKEN=\"$ACCESS_TOKEN\""
  echo ""
  
  # Also export to a file for easy sourcing
  echo "export ACCESS_TOKEN=\"$ACCESS_TOKEN\"" > .token.env
  echo "💾 Token saved to .token.env (source it: source .token.env)"
else
  echo "❌ Failed to get token:"
  echo "$RESPONSE" | jq '.'
  exit 1
fi

