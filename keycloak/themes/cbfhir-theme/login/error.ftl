<#-- Minimal error page for Keycloak theme, styled like backend login.html -->
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Error - Couchbase FHIR CE</title>
    <link rel="stylesheet" href="${url.resourcesPath}/css/style.css">
  </head>
  <body class="error-page">
    <div class="login-container">
      <div class="login-header">
        <h1>Couchbase FHIR CE</h1>
        <p>Authorization Error</p>
      </div>

      <div class="error-message">
        <#if message?has_content>
          ${message!''}
        <#elseif error?has_content>
          ${error!''}
        <#else>
          An unexpected error occurred.
        </#if>
      </div>

      <div style="margin-top:16px;">
          <a href="${url.loginUrl!''}">Back to login</a>
        </div>
    </div>
  </body>
</html>
