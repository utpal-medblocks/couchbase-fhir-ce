CREATE PRIMARY INDEX ON `fhir`.`Admin`.`users`;
CREATE PRIMARY INDEX ON `fhir`.`Admin`.`clients`;
CREATE PRIMARY INDEX ON `fhir`.`Admin`.`tokens`;
CREATE PRIMARY INDEX ON `fhir`.`Admin`.`authorizations`;
CREATE PRIMARY INDEX ON `fhir`.`Admin`.`consents`;
CREATE PRIMARY INDEX ON `fhir`.`Admin`.`jwks`;
-- Users lookup by email
CREATE INDEX idx_users_email ON `acme-fhir`.`Admin`.`users`(email);
-- Clients lookup by clientId
CREATE INDEX idx_clients_clientId ON `acme-fhir`.`Admin`.`clients`(clientId);
-- Tokens lookup by jti, sub, clientId
CREATE INDEX idx_tokens_jti ON `acme-fhir`.`Admin`.`tokens`(jti);
CREATE INDEX idx_tokens_sub ON `acme-fhir`.`Admin`.`tokens`(sub, exp);
-- Authorization codes by code
CREATE INDEX idx_auth_code ON `acme-fhir`.`Admin`.`authorizations`(code);
-- Active signing keys
CREATE INDEX idx_jwks_active ON `acme-fhir`.`Admin`.`jwks`(status, kid);
