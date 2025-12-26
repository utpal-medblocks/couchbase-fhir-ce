<#-- Minimal login template for Keycloak theme. Uses Keycloak variables provided in theme context. -->
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Sign In - ${realm.displayNameHtml!realm.name!''}</title>
    <link rel="stylesheet" href="${url.resourcesPath}/css/style.css">
  </head>
  <body class="login-page">
    <div class="login-container">
      <div class="login-header">
        <h1>Couchbase FHIR CE</h1>
        <p>Sign in to continue</p>
      </div>

      <#-- Show error or info messages if present -->
      <#if message?has_content>
        <div class="error-message">${message!''}</div>
      </#if>
      <#if error?has_content>
        <div class="error-message">${error!''}</div>
      </#if>

      <form action="${url.loginAction!''}" method="post">
        <input type="hidden" name="_csrf" value="${csrf!''}" />

        <div class="form-group">
          <label for="username">Username</label>
          <input type="text" id="username" name="username" value="${username!''}" required autofocus />
        </div>

        <div class="form-group">
          <label for="password">Password</label>
          <input type="password" id="password" name="password" required />
        </div>

        <div class="buttons">
          <button type="submit" class="btn-primary">Sign In</button>
        </div>
      </form>
    </div>
  </body>
</html>
