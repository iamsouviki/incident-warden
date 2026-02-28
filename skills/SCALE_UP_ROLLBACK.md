# Skill: SCALE_UP / ROLLBACK

Two related Kubernetes skills for managing deployment scale and version rollback via `kubectl` and `helm`.

---

## Action Key Formats

### SCALE_UP

```
SCALE_UP:<deployment-name>:<replica-count>[:<namespace>]
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `deployment-name` | Kubernetes Deployment name | — |
| `replica-count` | Target number of replicas | — |
| `namespace` | Kubernetes namespace | `default` |

**Examples:**
```
SCALE_UP:my-service:3
SCALE_UP:api-gateway:5:production
SCALE_UP:worker-deployment:10:batch-jobs
```

### ROLLBACK

```
ROLLBACK:<release-or-deployment>:<revision>[:<namespace>]
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `release-or-deployment` | Helm release name or Deployment name | — |
| `revision` | Revision number to roll back to (`0` = previous) | `0` |
| `namespace` | Kubernetes namespace | `default` |

**Examples:**
```
ROLLBACK:my-service:0
ROLLBACK:api-gateway:3:production
ROLLBACK:payment-service:2:prod-payments
```

---

## SCALE_UP Execution

```bash
kubectl scale deployment/<deployment-name> --replicas=<count> -n <namespace>
```

Runs via `ProcessBuilder`. The `kubectl` binary must be on the PATH (or configure `mcp.kubectl.binary` to the full path).

**Uses the kubeconfig from:**
1. `KUBECONFIG` environment variable
2. `~/.kube/config` (default)
3. In-cluster service account (if MCP runs inside a Pod)

---

## ROLLBACK Execution

Attempts Helm rollback first, then falls back to `kubectl rollout undo`:

```bash
# Helm rollback (if helm binary is present and release exists)
helm rollback <release> <revision> -n <namespace>

# Kubectl fallback
kubectl rollout undo deployment/<name> --to-revision=<revision> -n <namespace>
```

Revision `0` means "previous revision" for both Helm and kubectl.

---

## Return Values

### SCALE_UP success
```json
{
  "success": true,
  "deployment": "my-service",
  "targetReplicas": 3,
  "namespace": "default",
  "output": "deployment.apps/my-service scaled",
  "durationMs": 412
}
```

### ROLLBACK success
```json
{
  "success": true,
  "release": "api-gateway",
  "revision": 3,
  "namespace": "production",
  "output": "Rollback was a success! Happy Helming!",
  "durationMs": 1840
}
```

---

## SOP Example (SQL)

```sql
INSERT INTO sop_procedure (sop_id, step_number, title, description, action_type, execution_order, requires_approval)
VALUES
  -- Auto-scale during high load incident
  (7, 1, 'Scale Up API Gateway',
   'Increase API gateway replicas to handle traffic spike',
   'SCALE_UP:api-gateway:5:production', 10, true),

  (7, 2, 'Verify Gateway Health',
   'Confirm all new pods are ready and serving traffic',
   'CHECK_URL:http://api-gateway.production.svc/health:200', 20, false),

  -- Rollback a bad deployment
  (8, 1, 'Roll Back Payment Service',
   'Roll back payment service to the previous Helm release revision',
   'ROLLBACK:payment-service:0:prod-payments', 10, true),

  (8, 2, 'Check Payment Service Health',
   'Verify payment service is accepting requests after rollback',
   'CHECK_URL:http://payment-service.prod-payments.svc/health:200', 20, false);
```

---

## Configuration

```yaml
mcp:
  kubectl:
    binary: kubectl       # full path if not on PATH, e.g. /usr/local/bin/kubectl
    kubeconfig: ""        # empty = use KUBECONFIG env or ~/.kube/config
  helm:
    binary: helm
    timeout: 120s         # Helm operation timeout
```

---

## RBAC Requirements

The service account (or user) running MCP needs at minimum:

```yaml
# For SCALE_UP
- apiGroups: ["apps"]
  resources: ["deployments/scale"]
  verbs: ["update"]

# For ROLLBACK (kubectl)
- apiGroups: ["apps"]
  resources: ["deployments"]
  verbs: ["get", "patch", "update"]
  
# For Helm ROLLBACK
- apiGroups: ["*"]
  resources: ["*"]
  verbs: ["*"]    # Helm needs broad access for lifecycle management
```

---

## Limitations

- `SCALE_UP` only scales Deployments (not StatefulSets, DaemonSets)
- `ROLLBACK` with Helm: the Helm release history must not have been deleted
- `kubectl rollout undo` requires at least 2 revisions in the rollout history
- Both skills run synchronously but do not wait for pod readiness — add `CHECK_URL` procedures to verify
- Does not support multi-cluster (uses single kubeconfig context)
