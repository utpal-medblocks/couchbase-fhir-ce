// Test proxy configuration
console.log("Testing proxy configuration...");

// Test if we can reach the backend directly
fetch("http://localhost:8080/api/connections/active")
  .then((response) => {
    console.log("Direct backend call status:", response.status);
    return response.json();
  })
  .then((data) => console.log("Direct backend response:", data))
  .catch((error) => console.log("Direct backend error:", error));

// Test if proxy is working
fetch("/api/connections/active")
  .then((response) => {
    console.log("Proxy call status:", response.status);
    return response.json();
  })
  .then((data) => console.log("Proxy response:", data))
  .catch((error) => console.log("Proxy error:", error));
