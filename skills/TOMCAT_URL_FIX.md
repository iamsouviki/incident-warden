# SOP: Tomcat API URL Not Accessible on Linux

> **SOP ID:** SOP-TOMCAT-URL-001  
> **Version:** 2.1  
> **Severity:** SEV-2 (High)  
> **Category:** APPLICATION  
> **Platform:** Linux (RHEL / Ubuntu / CentOS)  
> **Last Updated:** March 2, 2026  
> **MCP Auto-Resolve:** Yes (if confidence ≥ 0.85 and risk ≤ LOW)

---

## Incident Profile

| Attribute | Value |
|-----------|-------|
| **Title** | Tomcat API URL Not Accessible |
| **Impact** | API consumers receive 502/503/Connection Refused |
| **Affected Service** | Tomcat Application Server (port 8080/8443) |
| **URL Pattern** | `http(s)://<hostname>:<port>/api/v1/*` |
| **Expected MTTR** | ≤ 30 minutes |

---

## MCP Action Plan

```json
{
  "actions": [
    "CHECK_URL:http://{{TARGET_HOST}}:8080/api/v1/health:200",
    "RESTART_SERVICE:tomcat",
    "CHECK_URL:http://{{TARGET_HOST}}:8080/api/v1/health:200",
    "CLEAR_CACHE:redis"
  ]
}
```

---

## Quick Diagnostic Steps

### 1. Verify the symptom
```bash
curl -v --connect-timeout 10 http://<HOSTNAME>:8080/api/v1/health
```

### 2. Check Tomcat process
```bash
sudo systemctl status tomcat
ps aux | grep -i "[t]omcat"
```

### 3. Check port binding
```bash
sudo ss -tlnp | grep 8080
sudo lsof -i :8080
```

### 4. Test from localhost
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/health
```

### 5. Check logs
```bash
sudo tail -100 $CATALINA_HOME/logs/catalina.out
sudo grep -i "error\|exception\|SEVERE\|OutOfMemory" $CATALINA_HOME/logs/catalina.out | tail -30
```

---

## Decision Matrix

| Symptom | Root Cause | Fix |
|---------|-----------|-----|
| Process dead | Tomcat crashed | Restart (§A) |
| Port not bound | Port conflict | Kill + restart (§B) |
| Localhost OK, external fails | Firewall | Open port (§C) |
| HTTP 404/500 | Deploy failure | Redeploy (§D) |
| OutOfMemoryError | JVM OOM | Increase heap (§E) |
| SSL error | Cert expired | Replace cert (§F) |
| 502 from proxy | Proxy miscfg | Fix proxy (§G) |

---

## Remediation Procedures

### A. Tomcat Service Not Running

```bash
sudo systemctl stop tomcat 2>/dev/null
sudo pkill -f 'catalina' 2>/dev/null
sudo rm -rf $CATALINA_HOME/work/* $CATALINA_HOME/temp/*
sudo systemctl start tomcat
sleep 15
sudo systemctl status tomcat
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/health
```

### B. Port Conflict

```bash
sudo lsof -i :8080
sudo kill -15 <CONFLICTING_PID>
sudo systemctl restart tomcat
sudo ss -tlnp | grep 8080
```

### C. Firewall Block

```bash
# RHEL/CentOS
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload

# Ubuntu
sudo iptables -A INPUT -p tcp --dport 8080 -j ACCEPT
```

### D. Deployment Failure

```bash
ls -la $CATALINA_HOME/webapps/
sudo rm -rf $CATALINA_HOME/webapps/api/
sudo systemctl restart tomcat
sleep 20
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/health
```

### E. JVM Out of Memory

```bash
sudo systemctl stop tomcat
sudo tee $CATALINA_HOME/bin/setenv.sh << 'EOF'
export CATALINA_OPTS="-Xms512m -Xmx2048m -XX:+HeapDumpOnOutOfMemoryError"
EOF
sudo chmod +x $CATALINA_HOME/bin/setenv.sh
sudo rm -rf $CATALINA_HOME/work/*
sudo systemctl start tomcat
```

### F. SSL Certificate Issue

```bash
echo | openssl s_client -connect localhost:8443 2>/dev/null | openssl x509 -noout -dates
grep -A5 "SSLHostConfig" $CATALINA_HOME/conf/server.xml
```

### G. Reverse Proxy (Nginx 502)

```bash
sudo grep -r "proxy_pass" /etc/nginx/
sudo tail -30 /var/log/nginx/error.log
sudo nginx -t
sudo systemctl reload nginx
```

---

## Post-Remediation Validation

```bash
# All must pass:
curl -s http://localhost:8080/api/v1/health          # → 200
curl -s http://<HOSTNAME>:8080/api/v1/health         # → 200
systemctl is-active tomcat                            # → active
ss -tlnp | grep 8080                                 # → java/<PID>
```

---

## Escalation

| Level | Role | When |
|-------|------|------|
| L1 | NOC Operator | Initial triage |
| L2 | Senior SRE | SOP fails after 2 attempts |
| L3 | Platform Eng | JVM/app-level issues |
| L4 | VP Engineering | SEV-1 / data loss risk |

