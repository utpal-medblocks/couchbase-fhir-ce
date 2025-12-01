# Overview

This document outlines the implementation of OAuth 2.0 authentication for the Couchbase FHIR Server. The goal is to provide secure API access through Bearer tokens while maintaining simplicity for internal use cases.

**Note** This is only for OAuth autentication and separate from SMART.

# Architecture

These components will be added.

1. A user management page
2. A token authenticaton page
3. A token management page
4. A user button in the AppBar (Shows profile pic if availabe, or initials)
   1. Profile
   2. Logout

# User Managament

## Admin Users

Example: John Doe, a super admin, starts Couchbase FHIR Server.
On Start:

- Challenged with login page. This will also have Google/Github but wont work first time.
- Enters userid/password from config.yaml
- Then from Admin page, deploys "fhir' bucket.
- Then goes to Users page and creates new user (himself)
  **User Record Example:**

```json
{
  "id": "john.doe",
  "username": "John Doe",
  "email": "john.doe@google.com", // john.doe@hospital.com
  "passwordHash": "bcrypt...",
  "role": "admin", // api_user
  "authMethod": "google", // or "local"
  "status": "active",
  "createdBy": "admin@couchbase.com",
  "createdAt": "2025-11-18T00:45:00Z",
  "lastLogin": null
}
```

- If John logs out and logs in, that login page will show authenticate by Google as well as userid/password. Only John knows this.
- John then proceeds to create other users (fixes their roles) with their emails.
- When other users login, their role is not admin and they will not not see the regular UI
-

# Token Management

- Separate page http://<ip>/tokens (?)
- Users login and are challenged with authentication (also allow Google/Github)
- Once authenticated, leads to Tokens page
- Click on generate new token:
  - Ask for App Name (hmmm...)
  - Generate token for user to copy
- Maintain Token table for user to manage, delete etc.

# Audit

With the above in place, the meta for an inserted doc will have:

```json
"meta": {
  "tag": [
    {
      "system": "http://couchbase.com/fhir/audit",
      "code": "created-by",
      "display": "john.doe via Mobile Chart App"
    }
  ]
}
```

# Benefits

### For Developers

- ✅ Simple token generation via web UI
- ✅ No complex OAuth flows for basic use
- ✅ Bearer tokens work with any HTTP client
- ✅ Clear audit trail of API usage

### For Administrators

- ✅ Centralized user management
- ✅ Token revocation capabilities
- ✅ User activity monitoring
- ✅ Secure default configurations

### For Security

- ✅ No hardcoded credentials in production
- ✅ JWT-based stateless authentication
- ✅ User identity in all operations
- ✅ Configurable token expiration
