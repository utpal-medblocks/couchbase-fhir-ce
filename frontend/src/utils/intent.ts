// Generic intent encode/decode helpers using URL-safe Base64 JSON
export function encodeIntent(obj: any): string {
  const json = JSON.stringify(obj);
  // btoa is available in browsers
  const b64 = btoa(unescape(encodeURIComponent(json)));
  // URL-safe: + -> -, / -> _, remove padding =
  return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function decodeIntent(str: string): any {
  if (!str) return null;
  // Restore padding
  let b64 = str.replace(/-/g, "+").replace(/_/g, "/");
  while (b64.length % 4) b64 += "=";
  try {
    const json = decodeURIComponent(escape(atob(b64)));
    return JSON.parse(json);
  } catch (e) {
    console.error('Failed to decode intent', e);
    return null;
  }
}
