---
id: user-management
title: User & Token Management
sidebar_label: User Management
---

# 🧩 User & Token Management

## Overview

Couchbase FHIR CE provides a **simple, role-based authentication model** designed for both developer productivity and SMART-on-FHIR compliance.  
The design balances ease of use (for Postman testing) with security and auditability.

---

## 🎭 User Roles

| Role                     | Description                                       | UI Access                    | Token Permissions                 |
| ------------------------ | ------------------------------------------------- | ---------------------------- | --------------------------------- |
| **Admin**                | Full control of Admin UI and system configuration | All pages                    | Create / view / revoke any token  |
| **Developer**            | Builds and tests APIs; limited admin access       | All pages _except_ **Users** | Create / view / revoke own tokens |
| _(optional)_ **Auditor** | Read-only dashboards and logs                     | Read-only                    | View only                         |

**Bootstrap:**  
On first startup, the system reads `config.yaml` and creates the initial Admin user.  
This user authenticates via local credentials and can later add new users.

---

## 🔐 Authentication Types

- **Local** – username + password stored securely (Argon2id hashed).
- **Social / SSO** – identity provided by an external provider (Google, GitHub, etc.).
- **No “App User” records** – this concept is omitted for simplicity.
  > Applications authenticate through SMART client registration instead.

---

## 🔑 Token Model

Two complementary token types exist:

### 1. Developer Tokens (Pre-Authorized)

| Property         | Value                                                  |
| ---------------- | ------------------------------------------------------ |
| **Purpose**      | Manual API testing (Postman, CLI scripts)              |
| **Scopes**       | Fixed; predetermined by user role                      |
| **Consent**      | None required                                          |
| **Lifetime**     | Long-lived (e.g., 7–30 days)                           |
| **Visibility**   | Developers see only their own tokens; Admins see all   |
| **UI Flow**      | `Tokens → Create Token` dialog (App name + expiry)     |
| **Audit Fields** | `createdBy`, `appName`, `ip`, `userAgent`, `expiresAt` |

Developers simply copy the **Bearer JWT** and use it directly — no redirect or OAuth flow.

---

### 2. SMART Tokens (User-Authorized)

| Property           | Value                                                                              |
| ------------------ | ---------------------------------------------------------------------------------- |
| **Purpose**        | Registered SMART-on-FHIR apps                                                      |
| **Scopes**         | Requested at runtime; user grants consent                                          |
| **Consent Screen** | Displays requested scopes and patient context                                      |
| **Lifetime**       | Short-lived access JWT + optional refresh token                                    |
| **Flow**           | OAuth 2.1 / SMART (authorization_code + PKCE)                                      |
| **Registration**   | Developers or Admins register apps and receive client ID (+ secret, redirect URIs) |

When creating a token, developers choose between:

- **Create Token** → pre-authorized developer JWT
- **Create Client** → SMART app registration (client ID + secret)

---

## 📋 Scoping Policy

- **Developer Tokens:** inherit fixed scopes from role defaults
  - Admin → `system/*.*`
  - Developer → `patient/*.read`, `patient/*.search`, limited writes
- **SMART Tokens:** dynamically scoped via consent
- All issued scopes must be a **subset** of role or client `allowed_scopes`.

---

## 🧭 Admin UI Pages

| Page                    | Visible To        | Purpose                         |
| ----------------------- | ----------------- | ------------------------------- |
| **Login**               | All users         | Local or SSO authentication     |
| **Users**               | Admin only        | Manage users, roles, and status |
| **Tokens → My Tokens**  | Developer         | Manage own developer tokens     |
| **Tokens → All Tokens** | Admin             | Global token management         |
| **Clients (SMART)**     | Developer + Admin | Register and manage SMART apps  |

---

## 🧾 Audit & Security

- Every user, token, and client action is **audited** (`Admin.audit`).
- **JWTs:** RS256 signed with short claims (`sub`, `scp`, `iss`, `aud`, `exp`, `jti`).
- **Passwords / Client Secrets:** stored as Argon2id hashes only.
- **MFA:** optional for Admins.
- **Revocation:** maintain lightweight denylist by `jti`.
- **Transport:** HTTPS / TLS 1.2+ required.

---

## ⚙️ Lifecycle Summary

| Flow                          | Steps                                                        |
| ----------------------------- | ------------------------------------------------------------ |
| **Developer Token**           | User → Tokens → Create Token → Copy JWT → Use in Postman     |
| **Admin Token Mgmt**          | Admin → Tokens → View / Revoke any token                     |
| **SMART Client Registration** | Developer → Clients → Create Client → Get client ID / secret |
| **SMART Auth Flow**           | App → Redirect user → Consent → Token endpoint → JWT         |

---

## ✅ Summary

- **Keep it simple:** only _Admin_ and _Developer_ roles to start.
- **Developer Tokens:** pre-authorized, long-lived JWTs — ideal for Postman.
- **SMART Tokens:** standard SMART-on-FHIR OAuth with user consent.
- **Audit everything**, **hash secrets**, and **restrict UI visibility** by role.

---

_Last updated: November 2025_
