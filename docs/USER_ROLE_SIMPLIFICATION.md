# User Role Simplification - Implementation Summary

**Date**: November 26, 2025  
**Status**: ✅ Complete

## Overview

Simplified user management to support only 2 UI-accessible roles: **admin** and **developer**. Patient and Practitioner roles exist only for sample data/testing but cannot login to the UI. This change improves security, ONC compliance, and simplifies the authentication flow.

---

## Key Changes

### 1. User Roles

| Role             | UI Access         | Scopes                   | Description                                                                                 |
| ---------------- | ----------------- | ------------------------ | ------------------------------------------------------------------------------------------- |
| **admin**        | ✅ Full Access    | `user/*.*`, `system/*.*` | Full control - Dashboard, Buckets, FHIR Resources, Users, Tokens, Client Registration, Logs |
| **developer**    | ✅ Limited Access | `user/*.*`               | Limited access - Only Tokens and Client Registration pages                                  |
| **patient**      | ❌ No Access      | Testing scopes           | Created during sample data load for SMART app testing only                                  |
| **practitioner** | ❌ No Access      | Testing scopes           | Created during sample data load for SMART app testing only                                  |

### 2. Authentication

- **All users now use LOCAL authentication only** - no more social auth options
- Username/password stored securely with BCrypt
- Login validation now rejects patient/practitioner role attempts with HTTP 403

### 3. Scope Management

- **Scopes are automatically assigned by backend based on role**
- No more manual scope selection in UI during user creation
- Admin gets: `["user/*.*", "system/*.*"]`
- Developer gets: `["user/*.*"]`
- Scopes auto-populate when generating tokens

### 4. Role-Based UI Navigation

**Admin sees:**

- Dashboard
- Buckets
- FHIR Resources
- Users
- API Tokens
- Client Registration
- Audit Logs
- System Logs

**Developer sees:**

- API Tokens
- Client Registration

**Developer redirect:** Upon login, developers are automatically redirected to `/tokens` page instead of dashboard.

### 5. Client Registration Page

Created a placeholder page at `/clients` for future SMART app client registration functionality. This page is accessible to both admin and developer roles.

**Planned features:**

- Dynamic client registration (OAuth 2.0 RFC 7591)
- Client credential management
- Redirect URI configuration
- Scope restrictions
- Grant type selection
- Client status management
- Audit logs

---

## Files Modified

### Backend

1. **`backend/src/main/java/com/couchbase/admin/users/model/User.java`**

   - Updated role documentation to reflect admin/developer/patient/practitioner
   - Updated scope auto-assignment logic
   - Clarified that authMethod is always "local"

2. **`backend/src/main/java/com/couchbase/admin/auth/controller/AuthController.java`**
   - Added role validation in login flow
   - Rejects login attempts from patient/practitioner roles with HTTP 403
   - Returns error: "Access denied: Your role does not have UI access"

### Frontend

3. **`frontend/src/pages/Users/Users.tsx`**

   - Removed scope selection UI (FormControl with multi-select)
   - Updated role dropdown to only show admin/developer
   - Removed authMethod dropdown (always local)
   - Removed scope validation in form submission
   - Updated helper text to indicate scopes are auto-assigned

4. **`frontend/src/services/usersService.ts`**

   - Updated User interface role type: `"admin" | "developer" | "patient" | "practitioner"`
   - Updated authMethod type to always be `"local"`
   - Updated `formatRole()` to handle patient/practitioner roles
   - Updated `formatAuthMethod()` to only show "Local"

5. **`frontend/src/pages/Auth/Login.tsx`**

   - Added role-based redirect logic
   - Developers redirect to `/tokens`
   - Admins redirect to `/dashboard`

6. **`frontend/src/routes/AppRoutes.tsx`**

   - Added `/clients` route for Client Registration page
   - Updated root route to show Dashboard directly

7. **`frontend/src/pages/Layout/MainLayout.tsx`**

   - Added `adminOnly` flag to menu items
   - Added Client Registration menu item
   - Hide Dashboard, Buckets, FHIR Resources from developers
   - Hide Audit Logs and System Logs from developers
   - Keep Users page visible but disabled for developers (admin-only)

8. **`frontend/src/pages/ClientRegistration/ClientRegistration.tsx`** (NEW)
   - Created placeholder page for future client registration functionality
   - Shows "Coming Soon" message with planned features

---

## Testing Checklist

### User Creation

- [x] Can create admin user with email/password
- [x] Can create developer user with email/password
- [x] Scopes are NOT shown in UI
- [x] AuthMethod dropdown removed (always local)
- [x] Role dropdown only shows admin/developer options

### Login Flow

- [x] Admin can login and see full navigation
- [x] Developer can login and redirects to /tokens
- [x] Patient/Practitioner cannot login (HTTP 403)
- [x] Error message shown for unauthorized roles

### Navigation

- [x] Admin sees all menu items
- [x] Developer only sees Tokens and Client Registration
- [x] Developer cannot access Dashboard, Buckets, FHIR Resources, Logs
- [x] Users page is disabled (grayed out) for developers

### Token Generation

- [x] Admin tokens include both user/_._ and system/_._ scopes
- [x] Developer tokens include only user/_._ scopes
- [x] Scopes are populated automatically based on user role

### Client Registration Page

- [x] Page is accessible at /clients
- [x] Shows "Coming Soon" placeholder
- [x] Lists planned features
- [x] Both admin and developer can access

---

## Security Improvements

1. **Simplified Attack Surface**: Only 2 roles can access UI, reducing complexity
2. **No Manual Scope Manipulation**: Scopes auto-assigned by backend prevents privilege escalation
3. **Role Enforcement**: Backend validates roles during login, not just frontend
4. **Clear Separation**: Patient/Practitioner roles clearly marked as test-only
5. **ONC Compliance**: Follows principle of least privilege

---

## Migration Notes

If you have existing users in the database:

1. **smart_user role**: Should be migrated to either patient or practitioner depending on use case
2. **social authMethod**: Should be migrated to local with password reset required
3. **Custom scopes**: Will be overwritten with role-based scopes on next login

---

## Next Steps

1. **Client Registration Implementation**: Build out the full client registration functionality
2. **Sample Data Update**: Ensure sample data creation includes patient/practitioner users
3. **Documentation Update**: Update user guides and API documentation
4. **Testing**: Comprehensive integration testing with SMART apps
5. **ONC Certification**: Prepare for certification testing with new role model

---

## Related Documentation

- [USER_MANAGEMENT.md](./USER_MANAGEMENT.md) - Overall user management guide
- [SMART_CONFIGURATION_SETUP.md](./SMART_CONFIGURATION_SETUP.md) - SMART on FHIR configuration
- [OAUTH_GUIDE.md](./OAUTH_GUIDE.md) - OAuth token flow documentation

---

## Questions or Issues?

Contact the development team or refer to the implementation in commit history.
