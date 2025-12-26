<#-- Minimal consent theme adapted for cbfhir -->
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8" />
  <title>Authorize ${client.clientId!''}</title>
  <link rel="stylesheet" href="${url.resourcesPath}/css/style.css">
</head>
<body>
  <div class="kc-consent">
    <div class="consent-container">
      <div class="consent-header">
        <h1>Authorize ${client.clientName!client.clientId}</h1>
        <p class="user-info">The application <strong>${client.clientName!client.clientId}</strong> is requesting access to your FHIR data on Couchbase FHIR CE.</p>
      </div>

      <#-- Scopes: Keycloak exposes oauth.scopes as list -->
      <#if oauth.scopes?has_content>
        <div class="scopes-section">
          <h3>Requested scopes</h3>
          <ul>
            <#list oauth.scopes as scope>
              <li>${scope!''}</li>
            </#list>
          </ul>
        </div>
      </#if>

      <form method="post" action="${url.action!''}">
        <input type="hidden" name="client_id" value="${client.clientId!''}" />
        <div class="actions">
          <button type="submit" name="cancel" value="true" class="btn-deny">Deny</button>
          <button type="submit" name="accept" value="true" class="btn-approve">Allow</button>
        </div>
      </form>
    </div>
  </div>
</body>
</html>
