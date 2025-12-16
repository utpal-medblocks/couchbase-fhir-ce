# Browser Caching Issue - Fixed

## Problem Description

When accessing `https://cbfhir.com` after restarting Docker, the browser was showing the **SMART User Login page** (OAuth 2.0 Authorization with purple gradient) instead of the **Admin UI Login page** (React SPA with "Admin UI Login" text).

This happened even after:

- Restarting Docker
- Hard refresh (Shift+Cmd+R)
- Not using SMART features for days

The correct behavior should be:

- `https://cbfhir.com/` → Admin UI Login (React SPA)
- `https://cbfhir.com/login` → SMART User Login (OAuth page)

## Root Cause

The issue was **browser caching** caused by:

1. **Missing Cache-Control headers in nginx** - The frontend nginx configuration didn't have cache-control headers for HTML files, so browsers aggressively cached the root page
2. **Missing Cache-Control headers in backend** - The Spring Boot controllers serving the SMART login and consent pages didn't set no-cache headers
3. **Browser persistence** - Once cached, the browser continued serving the stale page even after Docker restarts

## What Was Fixed

### 1. Frontend nginx Configuration (`frontend/nginx-default.conf`)

Added proper cache-control headers:

```nginx
location / {
    root   /usr/share/nginx/html;
    index  index.html index.htm;
    try_files $uri $uri/ /index.html;

    # Prevent caching of HTML files (especially index.html)
    location ~* \.html$ {
        add_header Cache-Control "no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0";
        add_header Pragma "no-cache";
        add_header Expires "0";
        expires off;
    }

    # Cache static assets (JS, CSS, images) for 1 year
    # These have hashed filenames so they can be cached indefinitely
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

**Effect:**

- HTML files (including `index.html`) will never be cached
- Static assets (JS, CSS, images) with hashed filenames can be cached for 1 year
- Users will always get the latest version of the Admin UI

### 2. Backend Login Controller (`backend/.../LoginController.java`)

Added cache-control headers:

```java
@GetMapping("/login")
public String login(HttpServletResponse response) {
    // Prevent caching of login page
    response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Expires", "0");
    return "login";
}
```

### 3. Backend Consent Controller (`backend/.../ConsentController.java`)

Added cache-control headers to the consent page as well:

```java
@GetMapping("/consent")
public String consent(..., HttpServletResponse response, ...) {
    // Prevent caching of consent page
    response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("Expires", "0");
    ...
}
```

## How to Deploy the Fix

### 1. Rebuild and Restart Docker Containers

```bash
# Navigate to project root
cd /path/to/couchbase-fhir-ce

# Rebuild frontend with new nginx config
docker-compose build fhir-admin

# Rebuild backend with updated controllers
docker-compose build fhir-server

# Restart all services
docker-compose down
docker-compose up -d
```

### 2. Clear Browser Cache (Important!)

Since your browser already has the wrong page cached, you need to clear it:

#### Option A: Clear All Browser Data (Recommended for testing)

**Chrome/Edge:**

1. Press `Cmd+Shift+Delete` (Mac) or `Ctrl+Shift+Delete` (Windows)
2. Select "Cached images and files"
3. Select "All time"
4. Click "Clear data"

**Safari:**

1. Safari → Settings → Privacy
2. Click "Manage Website Data"
3. Search for "cbfhir.com"
4. Remove all entries
5. Click "Done"

**Firefox:**

1. Press `Cmd+Shift+Delete` (Mac) or `Ctrl+Shift+Delete` (Windows)
2. Select "Cache"
3. Select "Everything"
4. Click "Clear Now"

#### Option B: Clear Specific Site Data (Faster)

**Chrome/Edge:**

1. Go to `https://cbfhir.com`
2. Press `F12` to open DevTools
3. Right-click the refresh button
4. Select "Empty Cache and Hard Reload"
5. Or: Application tab → Clear storage → "Clear site data"

**Safari:**

1. Go to `https://cbfhir.com`
2. Develop → Empty Caches
3. Or: Cmd+Option+E

**Firefox:**

1. Go to `https://cbfhir.com`
2. Press `F12`
3. Network tab → Click gear icon → Check "Disable cache"
4. Refresh page

### 3. Test the Fix

1. **Clear browser cache** (see above)
2. **Close all tabs** for `cbfhir.com`
3. **Open a new tab** and navigate to `https://cbfhir.com`
4. **Verify** you see the Admin UI Login page with:
   - Material-UI design
   - "Admin UI Login" text
   - Email and password fields
   - Dark theme toggle in the corner

### 4. Verify SMART Login Still Works

1. Navigate to `https://cbfhir.com/login` (note the `/login` path)
2. Verify you see the SMART User Login page with:
   - Purple gradient background
   - "OAuth 2.0 Authorization" text
   - Username and password fields

## Why This Won't Happen Again

### For New Users

- New users will automatically get the correct behavior with no caching

### For Existing Users (During Development)

If you're still developing and need to ensure no caching issues:

1. **Use DevTools with cache disabled:**

   - Open DevTools (F12)
   - Network tab → Check "Disable cache"
   - Keep DevTools open while testing

2. **Use Incognito/Private browsing:**

   - Incognito mode starts with a fresh cache every time

3. **Use cache-busting URL parameters (temporary):**
   - `https://cbfhir.com/?v=1` (change the number each time)

## Architecture Overview

```
Browser Request: https://cbfhir.com/
         ↓
    HAProxy (Port 80/443)
         ↓
    [Routes based on path]
         ↓
    ┌────────────────────────────┬──────────────────────────────┐
    │ / (root)                   │ /login, /oauth2, /fhir, /api │
    │ /dashboard, /users, etc.   │                              │
    ↓                            ↓                              │
backend-fhir-admin           backend-fhir-server              │
(React SPA via nginx)        (Spring Boot)                    │
         ↓                            ↓                        │
Admin UI Login               SMART User Login                 │
- Email/Password             - Username/Password              │
- Material-UI                - Purple gradient                │
- Admin features             - OAuth 2.0 flow                 │
```

## Additional Notes

### HAProxy Routing (haproxy.cfg)

The routing is working correctly:

- `/login` → backend-fhir-server (SMART login)
- `/oauth2/**` → backend-fhir-server (OAuth endpoints)
- `/fhir/**` → backend-fhir-server (FHIR API)
- `/api/**` → backend-fhir-server (Admin API)
- `/*` (default) → backend-fhir-admin (Admin UI)

The issue was never with HAProxy - it was purely browser caching preventing the correct page from being fetched.

### Session Cookies

Note that the application uses session cookies for OAuth flows:

```yaml
server:
  servlet:
    session:
      cookie:
        same-site: none
        secure: true
```

These are separate from the caching issue and work correctly with the fix.

## Troubleshooting

### Issue: Still seeing SMART login after deploying fix

**Solution:**

1. Verify containers are rebuilt: `docker-compose ps`
2. Check nginx config in running container:
   ```bash
   docker exec -it <fhir-admin-container> cat /etc/nginx/conf.d/default.conf
   ```
3. Clear browser cache completely (see above)
4. Try incognito mode to verify

### Issue: Both pages look the same

**Solution:**
They are different pages:

- **Admin UI Login**: React SPA, says "Admin UI Login", uses email field
- **SMART User Login**: HTML template, says "OAuth 2.0 Authorization", uses username field

If they look identical, clear cache and hard refresh.

### Issue: Getting 404 or connection refused

**Solution:**

1. Check if containers are running: `docker-compose ps`
2. Check HAProxy stats: `http://cbfhir.com/haproxy?stats` (admin/admin)
3. Check backend logs: `docker-compose logs fhir-server`
4. Check frontend logs: `docker-compose logs fhir-admin`

## Summary

The fix ensures:

- ✅ HTML files are never cached
- ✅ Login pages always show the latest version
- ✅ Static assets are still cached efficiently
- ✅ No more confusion between Admin and SMART login
- ✅ Shift+Cmd+R will work correctly going forward
- ✅ New tabs will always show the correct page

The changes are production-ready and follow best practices for:

- Security (no-cache for sensitive pages)
- Performance (aggressive caching for static assets)
- User experience (always seeing the latest UI)
