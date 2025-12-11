from dotenv import load_dotenv
import os
import requests


# Load environment variables from fhir/.env explicitly
load_dotenv()

# HAPI FHIR configuration - load from .env.hapi
load_dotenv(".env.hapi")
HAPI_FHIR_URL = os.getenv("HAPI_FHIR_URL")

"""
Couchbase FHIR configuration - prefer .env.cbfhir if present; fallback to .env.medplum
Supported options:
- Static token: CBFHIR_STATIC_BEARER
- Client credentials: CBFHIR_CLIENT_ID, CBFHIR_CLIENT_SECRET, CBFHIR_TOKEN_URL, CBFHIR_SCOPE
"""
load_dotenv(".env.cbfhir")
STATIC_BEARER = os.getenv("CBFHIR_STATIC_BEARER")
CBFHIR_FHIR_URL = os.getenv("CBFHIR_FHIR_URL")
CLIENT_ID = os.getenv("CBFHIR_CLIENT_ID")
CLIENT_SECRET = os.getenv("CBFHIR_CLIENT_SECRET")
TOKEN_URL = os.getenv("CBFHIR_TOKEN_URL")
SCOPE = os.getenv("CBFHIR_SCOPE", "system/*.*")

# Backward-compatible fallback to medplum-style env names if cb-fhir vars are not set
if not CLIENT_ID:
    load_dotenv(".env.medplum")
    os.getenv("MEDPLUM_FHIR_URL")  # kept for completeness; not used directly here
    CLIENT_ID = os.getenv("MEDPLUM_CLIENT_ID") or CLIENT_ID
    CLIENT_SECRET = os.getenv("MEDPLUM_CLIENT_SECRET") or CLIENT_SECRET
    TOKEN_URL = os.getenv("MEDPLUM_TOKEN_URL") or TOKEN_URL

# Check if required environment variables are loaded
if not STATIC_BEARER and (not CLIENT_ID or not CLIENT_SECRET or not TOKEN_URL):
    print("Warning: Missing auth configuration for Couchbase FHIR")
    print("Provide CBFHIR_STATIC_BEARER or CBFHIR_CLIENT_ID/SECRET/TOKEN_URL")

def get_access_token():
    if STATIC_BEARER:
        return STATIC_BEARER

    if not CLIENT_ID or not CLIENT_SECRET or not TOKEN_URL:
        raise ValueError("Missing client credentials or token URL in environment/.env")

    headers = {
        'Content-Type': 'application/x-www-form-urlencoded'
    }
    data = {
        'grant_type': 'client_credentials',
        'client_id': CLIENT_ID,
        'client_secret': CLIENT_SECRET,
        'scope': SCOPE,
    }

    response = requests.post(TOKEN_URL, headers=headers, data=data)
    if response.status_code == 200:
        return response.json().get('access_token')
    else:
        raise RuntimeError(f"Failed to get access token: {response.status_code} {response.text}")


def build_optional_auth_headers():
    """
    Returns a callable that produces headers including a fresh Bearer token when
    client credentials are configured; otherwise returns a callable that yields None.
    This keeps tokens current for long-running load tests.
    """
    def _supplier():
        try:
            token = get_access_token()
            if token:
                return {
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {token}",
                }
        except Exception as exc:
            print(f"Warning: failed to obtain access token ({exc}); proceeding without Authorization header")
            return None
        return None

    return _supplier
