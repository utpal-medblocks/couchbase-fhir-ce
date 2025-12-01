# Single-Tenant Architecture — Couchbase FHIR CE

## Rationale

As the project evolves toward full **SMART on FHIR** compliance, maintaining multiple FHIR-enabled buckets ("tenants") in a single Couchbase cluster introduces significant complexity without delivering proportional benefit. The single-tenant model simplifies architecture, security, and certification readiness while preserving the performance advantage of Couchbase.

### Why Simplify Now
- **SMART adds complexity:** Multi-tenant issuers, per-bucket users, client registrations, and JWKS key sets are hard to isolate correctly in a single cluster.
- **No control-plane bucket:** There is no dedicated global bucket (like Capella Control Plane) to safely host shared user, token, and client data.
- **Intended use:** Multiple buckets were primarily meant for **Dev/Stage/Prod** separation within the same organization, not for multi-customer tenancy.
- **Security isolation:** Avoids risk of cross-bucket token validation or data leakage.
- **Operational simplicity:** Easier configuration, consistent FTS indexes, and simplified management of keys, users, and clients.
- **Team efficiency:** A small development team can focus on correctness, performance, and compliance rather than managing tenant complexity.

---

## New Model — One FHIR Bucket per Cluster

- Each Couchbase FHIR CE deployment (cluster) manages **exactly one bucket named `fhir`**.
- All data, indexes, and auth artifacts reside within this single bucket.
- The bucket includes the following scopes:
  - `Resources` — FHIR resources (Patient, Observation, Encounter, etc.)
  - `Auth` — Users, Clients, Tokens, Authorizations, Consents, JWKS, and Audits
  - `Versions` and `Tombstones` — versioning and soft-delete support
- Admin UI and FHIR REST API both operate on this single logical tenant.

---

## Architecture Summary

```
          Ports 80/443
              │
        ┌─────▼──────┐
        │  HAProxy   │  TLS termination + routing
        └─────┬──────┘
              │
     ┌────────┼────────┐
     │        │        │
┌────▼───┐ ┌──▼─────┐ ┌▼────────┐
│ Admin  │ │  FHIR  │ │  Auth   │
│  UI    │ │ Server │ │ Server │
│ :80    │ │ :8080  │ │ :9000  │
└────────┘ └────────┘ └────────┘
      │         │         │
      └─────────┴─────────┘
              │
        ┌─────▼──────┐
        │ Couchbase  │
        │  Bucket:   │
        │   fhir     │
        └────────────┘
```

---

## Competitive Landscape

| Implementation | Tenancy Model | Notes |
|----------------|----------------|-------|
| **HAPI FHIR JPA Server** | Single database per deployment | Each environment (Dev/Stage/Prod) typically runs its own DB schema or instance. |
| **Medplum** | Single-tenant per hosted workspace | Multi-tenant supported only in the managed cloud offering. |
| **Microsoft FHIR Server (Azure API for FHIR)** | Single logical tenant per deployment | True multi-tenant isolation achieved only via managed service tier. |
| **Google Cloud Healthcare API** | Multi-tenant, but managed by Google’s control plane | Not replicable in open-source or self-managed setups. |
| **Couchbase FHIR CE (Proposed)** | Single-tenant per cluster (one `fhir` bucket) | Clean, secure, and performant for open-source users. |

This mirrors the industry trend: **open-source = single-tenant, cloud-managed = multi-tenant.**

---

## Pros & Cons

| Aspect | Single-Tenant (Chosen) | Multi-Tenant (Deferred) |
|---------|------------------------|--------------------------|
| **Architecture** | Simple and predictable | Complex routing and bucket mapping |
| **Security** | Clear boundary; one issuer, one JWKS | Risk of token cross-talk, leakage |
| **Performance** | Dedicated indexes and caches | Index sprawl, query contention |
| **Developer Experience** | Easier to test, debug, and certify | Requires tenant-aware logic everywhere |
| **Scalability** | Scale via more clusters or Capella projects | Complex scaling, shared resources |
| **Future Flexibility** | Foundation for Enterprise/Capella multi-tenant edition | Requires control-plane layer |

---

## Enterprise Outlook

The **Enterprise Edition** or a **managed Capella service** can later reintroduce multi-tenant capability via:
- A dedicated **control-plane bucket** or service to manage global users, clients, and keys.
- Per-tenant Couchbase scopes or isolated buckets, managed dynamically.
- Stronger cross-tenant security boundaries (e.g., isolated JWT issuers and signing keys).

For the **Community Edition**, the focus remains on:
- Bullet-proof single-tenant SMART on FHIR compliance
- Performance, reliability, and ease of deployment
- Zero external dependencies beyond Couchbase

---

**Decision:** Couchbase FHIR CE is now officially **single-tenant per cluster**, with a fixed bucket name **`fhir`**. This simplifies deployment, reduces risk, and ensures a secure and certifiable foundation for SMART on FHIR.

