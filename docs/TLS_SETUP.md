Enabling HTTPS for Couchbase FHIR CE with Let’s Encrypt and HAProxy
This guide shows how to expose a Couchbase FHIR CE demo endpoint over HTTPS using a free Let’s Encrypt certificate terminated at HAProxy. It assumes the standard three‑container deployment (HAProxy, FHIR server, FHIR admin UI) on a single EC2 instance.

1. Prerequisites
   An EC2 instance running the Couchbase FHIR CE Docker stack (via install.sh).

SSH access as a user with sudo.

A registered domain name, e.g. cbfhir.com, and control of its DNS.

The EC2 security group can be edited to open ports 80 and 443.​

2. Register a domain and point it to EC2
   Register a domain (e.g. cbfhir.com) with your preferred registrar (Route 53, etc.).​

In your DNS provider, create an A record:

Name: cbfhir.com

Type: A (IPv4)

Value: EC2 public IP (or Elastic IP)

TTL: 300 seconds

Verify from your workstation:

bash
nslookup cbfhir.com
curl -v http://cbfhir.com/dashboard
You should see it resolve to the EC2 IP and return the Couchbase FHIR CE UI over HTTP.​

3. Open ports 80 and 443 in the security group
   In the EC2 security group attached to this instance, add inbound rules:​

TCP 80 from 0.0.0.0/0

TCP 443 from 0.0.0.0/0

These are required for HTTP‑01 validation (port 80) and HTTPS traffic (port 443).​

4. Install Certbot on the EC2 host
   On the EC2 instance (host, not inside a container):

bash
sudo dnf install -y python3-pip python3-pyOpenSSL
sudo pip3 install certbot
sudo ln -s /usr/local/bin/certbot /usr/bin/certbot

certbot --version 5. Obtain a Let’s Encrypt certificate (HTTP‑01, standalone)
Stop anything listening on port 80 (typically your Docker stack):

bash
docker compose down # or your equivalent
sudo ss -tlnp | grep ':80' || echo "port 80 free"
Run Certbot with the standalone authenticator:

bash
sudo certbot certonly --standalone -d cbfhir.com
On success, Certbot writes:

/etc/letsencrypt/live/cbfhir.com/fullchain.pem

/etc/letsencrypt/live/cbfhir.com/privkey.pem​

6. Create the HAProxy PEM bundle
   Still on the host:

bash
sudo mkdir -p /etc/haproxy/certs
sudo cat /etc/letsencrypt/live/cbfhir.com/fullchain.pem \
 /etc/letsencrypt/live/cbfhir.com/privkey.pem \
 | sudo tee /etc/haproxy/certs/cbfhir.com.pem
This combined PEM is what HAProxy will load.

7. Mount certs into the HAProxy container
   In docker-compose.yml, ensure the haproxy service mounts both the config and certs directory:

text
services:
haproxy:
image: haproxy:2.9
volumes: - ./haproxy/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro - /etc/haproxy/certs:/etc/haproxy/certs:ro
ports: - "80:80" - "443:443"
depends_on: - fhir-admin - fhir-server 8. Update haproxy.cfg for HTTPS
In ./haproxy/haproxy.cfg, configure the frontend to listen on 80 and 443, terminate TLS, and redirect HTTP→HTTPS:

text
global
log stdout format raw daemon

defaults
mode http
log global
option httplog
option dontlognull
option dontlog-normal
timeout connect 5s
timeout client 30s
timeout server 30s
timeout http-request 5s

frontend http-in
bind _:80
bind _:443 ssl crt /etc/haproxy/certs/cbfhir.com.pem
http-request redirect scheme https unless { ssl_fc }
option forwardfor
option http-server-close
option dontlognull

    stats uri /haproxy?stats
    stats refresh 5s
    stats auth admin:admin
    stats show-legends

    acl is_api path_beg /api /fhir
    acl is_health path_beg /health
    use_backend backend-fhir-server if is_api
    use_backend backend-fhir-server if is_health
    default_backend backend-fhir-admin

backend backend-fhir-admin
balance roundrobin
option httpchk HEAD /
server frontend fhir-admin:80 check

backend backend-fhir-server
balance roundrobin
http-request set-header X-Forwarded-Proto http
http-request set-header X-Forwarded-Proto https if { ssl_fc }
option httpchk GET /health/readiness
http-check expect status 200
server backend fhir-server:8080 check inter 5s fall 3 rise 2 9. Restart the stack and test HTTPS
Restart:

bash
docker compose up -d
Verify from your workstation:

bash
curl -vk https://cbfhir.com/dashboard
You should see a valid certificate chain and HTTP/1.1 200 OK with the Couchbase FHIR CE dashboard HTML. Then open https://cbfhir.com/dashboard in a browser and confirm the padlock and UI load correctly.​

For Inferno and other SMART/FHIR clients, your FHIR base URL will now be:

https://cbfhir.com/fhir

10. Renewal and port 80 considerations
    Let’s Encrypt certificates are valid for 90 days; Certbot’s default is to renew around day 60 using the same HTTP‑01 challenge mechanism.​

If you keep port 80 open (recommended):

Renewals can happen automatically (e.g., via a cron that runs certbot renew and rebuilds cbfhir.com.pem, then reloads HAProxy).​

If you close port 80 to 0.0.0.0/0 after setup:

HTTP‑01 renewals will fail unless you temporarily reopen port 80 from the internet when renewing (and allow Certbot or HAProxy to serve the challenge on /.well-known/acme-challenge/\*).​

Alternatives:

Use DNS‑01 challenges with a DNS plugin, so renewals work without opening 80 at all.​

For most demo/test deployments, leaving port 80 open and redirecting everything to HTTPS is the simplest and is explicitly recommended by Let’s Encrypt for general web use.​
