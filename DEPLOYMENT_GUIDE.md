# Rentoza Production Deployment Guide

## 🚀 Overview

This guide covers deploying your Rentoza rent-a-car platform to production at `rentoza.rs`. The application has been hardened with environment-based configuration, secure cookie handling, proper logging, and transaction safety.

---

## 📋 Prerequisites

Before deploying, ensure you have:

- [ ] **Git history cleaned** of sensitive data (JWT secret, database password)
- [ ] **New secrets generated** (do NOT reuse the exposed ones)
- [ ] **Domain configured**: `rentoza.rs` for frontend, `api.rentoza.rs` for backend
- [ ] **SSL certificates** obtained (Let's Encrypt recommended)
- [ ] **MySQL database** set up (8.0+)
- [ ] **Java 17+** runtime environment
- [ ] **Node.js 18+** for frontend builds

---

## 🔐 Step 1: Generate New Secrets

### Generate JWT Secret (64 bytes, base64 encoded):
```bash
openssl rand -base64 64
```

### Generate Strong Database Password:
```bash
openssl rand -base64 32
```

**CRITICAL:** Store these securely (use a password manager or secrets vault). Never commit them to git.

---

## 🗄️ Step 2: Database Setup

### Create Production Database:
```sql
CREATE DATABASE rentoza CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'rentoza_user'@'%' IDENTIFIED BY 'YOUR_STRONG_PASSWORD';
GRANT ALL PRIVILEGES ON rentoza.* TO 'rentoza_user'@'%';
FLUSH PRIVILEGES;
```

### Enable SSL for Database Connection:
```sql
-- Ensure your MySQL server has SSL enabled
SHOW VARIABLES LIKE '%ssl%';
```

### Run Initial Schema:
```bash
# On first deployment, let Hibernate create tables
# Set spring.jpa.hibernate.ddl-auto=update for initial run
# Then change to 'validate' for subsequent deployments
```

---

## ⚙️ Step 3: Backend Configuration

### Set Environment Variables:

Create a `.env` file or set system environment variables:

```bash
# Database Configuration
export DB_URL="jdbc:mysql://your-db-host:3306/rentoza?useSSL=true&requireSSL=true&serverTimezone=UTC"
export DB_USERNAME="rentoza_user"
export DB_PASSWORD="your_generated_password"

# JWT Configuration
export JWT_SECRET="your_generated_jwt_secret"
export JWT_EXPIRATION_MS="900000"  # 15 minutes
export JWT_ISSUER="rentoza"
export JWT_AUDIENCE="rentoza-api"

# CORS Configuration
export CORS_ORIGINS="https://rentoza.rs,https://www.rentoza.rs"

# Cookie Configuration (Production)
export COOKIE_SECURE="true"
export COOKIE_DOMAIN="rentoza.rs"
export COOKIE_SAME_SITE="Strict"

# Server Configuration
export SERVER_PORT="8443"
export SSL_ENABLED="false"  # Set to true if terminating SSL in Spring Boot
# If using SSL in Spring Boot:
# export SSL_KEYSTORE="/path/to/keystore.p12"
# export SSL_KEYSTORE_PASSWORD="keystore_password"
# export SSL_KEY_ALIAS="rentoza"

# Logging
export LOG_LEVEL_ROOT="WARN"
export LOG_LEVEL_APP="INFO"
```

### Create application.properties:

Based on the provided templates, copy `application-prod.properties` to `application.properties` in production:

```bash
cd Rentoza/src/main/resources
cp application-prod.properties application.properties
```

The properties file will read from environment variables, so no secrets are stored in files.

---

## 🏗️ Step 4: Build Backend

### Build JAR:
```bash
cd Rentoza
./mvnw clean package -DskipTests
```

### Verify JAR:
```bash
ls -lh target/Rentoza-*.jar
```

---

## 🎨 Step 5: Frontend Configuration

### Update Production Environment:

Edit `rentoza-frontend/src/environments/environment.prod.ts`:

```typescript
export const environment = {
  production: true,
  baseApiUrl: 'https://api.rentoza.rs/api'
};
```

### Build Frontend:
```bash
cd rentoza-frontend
npm install --production
npm run build --configuration=production
```

### Verify Build:
```bash
ls -lh dist/rentoza-frontend/
```

---

## 🌐 Step 6: Web Server Configuration

### Option A: Nginx (Recommended)

Create `/etc/nginx/sites-available/rentoza`:

```nginx
# Frontend (rentoza.rs)
server {
    listen 443 ssl http2;
    server_name rentoza.rs www.rentoza.rs;

    ssl_certificate /etc/letsencrypt/live/rentoza.rs/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/rentoza.rs/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    root /var/www/rentoza-frontend;
    index index.html;

    # Security Headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header Referrer-Policy "same-origin" always;

    # Angular routing
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Gzip compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml text/javascript;
}

# Backend API (api.rentoza.rs)
server {
    listen 443 ssl http2;
    server_name api.rentoza.rs;

    ssl_certificate /etc/letsencrypt/live/api.rentoza.rs/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.rentoza.rs/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    # Proxy to Spring Boot
    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket support (if needed)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # Security Headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
}

# HTTP to HTTPS redirect
server {
    listen 80;
    server_name rentoza.rs www.rentoza.rs api.rentoza.rs;
    return 301 https://$server_name$request_uri;
}
```

Enable the site:
```bash
sudo ln -s /etc/nginx/sites-available/rentoza /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

### Option B: Apache

```apache
<VirtualHost *:443>
    ServerName rentoza.rs
    ServerAlias www.rentoza.rs

    DocumentRoot /var/www/rentoza-frontend

    SSLEngine on
    SSLCertificateFile /etc/letsencrypt/live/rentoza.rs/fullchain.pem
    SSLCertificateKeyFile /etc/letsencrypt/live/rentoza.rs/privkey.pem

    <Directory /var/www/rentoza-frontend>
        Options -Indexes +FollowSymLinks
        AllowOverride All
        Require all granted

        # Angular routing
        RewriteEngine On
        RewriteBase /
        RewriteRule ^index\.html$ - [L]
        RewriteCond %{REQUEST_FILENAME} !-f
        RewriteCond %{REQUEST_FILENAME} !-d
        RewriteRule . /index.html [L]
    </Directory>

    # Security Headers
    Header always set Strict-Transport-Security "max-age=31536000; includeSubDomains"
    Header always set X-Content-Type-Options "nosniff"
    Header always set X-Frame-Options "SAMEORIGIN"
</VirtualHost>
```

---

## 🐳 Step 7: Deploy Backend (Systemd Service)

### Create Service File:

Create `/etc/systemd/system/rentoza.service`:

```ini
[Unit]
Description=Rentoza API Server
After=network.target mysql.service

[Service]
Type=simple
User=rentoza
Group=rentoza
WorkingDirectory=/opt/rentoza

# Load environment variables
EnvironmentFile=/opt/rentoza/.env

# Java command
ExecStart=/usr/bin/java \
    -Xmx512m \
    -Xms256m \
    -Dspring.profiles.active=prod \
    -jar /opt/rentoza/Rentoza.jar

# Restart policy
Restart=on-failure
RestartSec=10

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=rentoza

# Security
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

### Deploy Application:
```bash
sudo mkdir -p /opt/rentoza
sudo cp target/Rentoza-*.jar /opt/rentoza/Rentoza.jar
sudo cp .env /opt/rentoza/.env
sudo chown -R rentoza:rentoza /opt/rentoza
sudo chmod 600 /opt/rentoza/.env

sudo systemctl daemon-reload
sudo systemctl enable rentoza
sudo systemctl start rentoza
sudo systemctl status rentoza
```

### View Logs:
```bash
sudo journalctl -u rentoza -f
```

---

## ✅ Step 8: Post-Deployment Verification

### Test Backend Health:
```bash
curl https://api.rentoza.rs/actuator/health
```

### Test CORS:
```bash
curl -H "Origin: https://rentoza.rs" \
     -H "Access-Control-Request-Method: POST" \
     -H "Access-Control-Request-Headers: Authorization" \
     -X OPTIONS https://api.rentoza.rs/api/auth/login
```

### Test Registration:
```bash
curl -X POST https://api.rentoza.rs/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Test",
    "lastName": "User",
    "email": "test@example.com",
    "phoneNumber": "0641234567",
    "password": "TestPass123",
    "role": "USER"
  }'
```

### Test Frontend:
- Visit `https://rentoza.rs`
- Open browser DevTools → Network tab
- Test registration, login, car browsing
- Verify cookies are `HttpOnly`, `Secure`, `SameSite=Strict`

---

## 🔒 Security Checklist

- [ ] **JWT secret** is strong (64+ bytes) and never committed to git
- [ ] **Database password** is strong and stored securely
- [ ] **SSL/TLS** enabled on all endpoints
- [ ] **CORS** configured for production domains only
- [ ] **Cookies** use `HttpOnly`, `Secure`, and `SameSite=Strict`
- [ ] **HSTS header** enabled (max-age=31536000)
- [ ] **CSP header** configured to restrict resources
- [ ] **Database SSL** enabled (`useSSL=true&requireSSL=true`)
- [ ] **DDL auto** set to `validate` (not `update`) in production
- [ ] **SQL logging** disabled (`spring.jpa.show-sql=false`)
- [ ] **Error messages** don't leak internal details
- [ ] **Rate limiting** implemented (consider nginx `limit_req_zone`)
- [ ] **Firewall** configured to allow only necessary ports (80, 443, 3306)
- [ ] **Backup strategy** in place for database

---

## 📊 Monitoring & Maintenance

### Log Rotation:
```bash
sudo nano /etc/logrotate.d/rentoza
```

```
/var/log/rentoza/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 0640 rentoza rentoza
    sharedscripts
    postrotate
        systemctl reload rentoza > /dev/null 2>&1 || true
    endscript
}
```

### Database Backups:
```bash
# Daily backup script
0 2 * * * mysqldump -u rentoza_user -p'password' rentoza | gzip > /backups/rentoza_$(date +\%Y\%m\%d).sql.gz
```

### Monitoring Endpoints:
- Health: `https://api.rentoza.rs/actuator/health`
- Metrics: Configure Spring Boot Actuator metrics endpoint (secured)

---

## 🐛 Troubleshooting

### Backend Won't Start:
```bash
# Check logs
sudo journalctl -u rentoza -n 100

# Common issues:
# - Missing environment variables
# - Database connection failure
# - Port already in use
```

### CORS Errors:
```bash
# Verify CORS_ORIGINS environment variable
echo $CORS_ORIGINS

# Should be: https://rentoza.rs,https://www.rentoza.rs
```

### Cookie Not Being Set:
```bash
# Verify cookie settings
curl -v https://api.rentoza.rs/api/auth/login

# Look for Set-Cookie header with:
# - HttpOnly
# - Secure
# - SameSite=Strict
```

### Database Connection Issues:
```bash
# Test MySQL connection
mysql -h your-db-host -u rentoza_user -p

# Verify SSL is enabled
SHOW VARIABLES LIKE '%ssl%';
```

---

## 🔄 Updates & Rollbacks

### Deploy New Version:
```bash
# Build new JAR
./mvnw clean package -DskipTests

# Stop service
sudo systemctl stop rentoza

# Backup current JAR
sudo cp /opt/rentoza/Rentoza.jar /opt/rentoza/Rentoza.jar.backup

# Deploy new JAR
sudo cp target/Rentoza-*.jar /opt/rentoza/Rentoza.jar

# Start service
sudo systemctl start rentoza

# Verify
sudo systemctl status rentoza
```

### Rollback:
```bash
sudo systemctl stop rentoza
sudo cp /opt/rentoza/Rentoza.jar.backup /opt/rentoza/Rentoza.jar
sudo systemctl start rentoza
```

---

## 📞 Support

For issues specific to the Rentoza codebase:
1. Check application logs: `sudo journalctl -u rentoza -f`
2. Check Nginx logs: `sudo tail -f /var/log/nginx/error.log`
3. Review Spring Boot actuator health endpoint
4. Verify all environment variables are set correctly

---

## 📝 Quick Reference

| Component | Location | Port | URL |
|-----------|----------|------|-----|
| Frontend | `/var/www/rentoza-frontend` | 443 | https://rentoza.rs |
| Backend | `/opt/rentoza` | 8080 | https://api.rentoza.rs |
| Database | MySQL | 3306 | localhost |
| Logs | systemd journal | - | `journalctl -u rentoza` |

---

**🎉 Your Rentoza platform is now production-ready!**

Remember to:
- Monitor logs regularly
- Keep dependencies updated
- Perform regular security audits
- Test backups periodically
- Scale resources as user base grows

Good luck with your launch! 🚗✨
