package com.company.mcp.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Risk Evaluator Agent - Phase 6 Implementation.
 * Evaluates risk factors and applies 9-layer guardrails.
 * 
 * Phase 6 Implementation:
 * - Risk scoring from incident characteristics (P1 severity, blast radius, etc.)
 * - Guardrail violation detection (9 layers)
 * - Change window validation
 * - System health evaluation
 * - Auto-escalation for high-risk incidents
 * 
 * 9-Layer Guardrails:
 * 1. Production environment protection
 * 2. Customer impact assessment
 * 3. Data sensitivity validation
 * 4. Transaction consistency checks
 * 5. Backup/recovery availability
 * 6. Change window validation
 * 7. System health metrics
 * 8. Current deployments check
 * 9. Recent incident frequency
 */
@Slf4j
@Component
public class RiskEvaluatorAgent extends BaseAgent {

    // Guardrail thresholds
    private static final double HIGH_RISK_THRESHOLD = 0.75;
    private static final double MEDIUM_RISK_THRESHOLD = 0.50;

    public RiskEvaluatorAgent() {
        super("RiskEvaluatorAgent");
    }

    @Override
    public AgentContext execute(AgentContext context) throws AgentExecutionException {
        logExecution(context, "RiskEvaluatorAgent: Evaluating risk factors and guardrails");
        
        try {
            List<String> violations = new ArrayList<>();
            double riskScore = 0.0;
            
            // Layer 1: Production Environment Protection
            double prodRisk = evaluateProductionRisk(context);
            riskScore += prodRisk * 0.15;
            if (prodRisk > 0.8) {
                violations.add("Layer-1: Production environment - High risk of customer impact");
            }
            
            // Layer 2: Customer Impact Assessment
            double customerRisk = evaluateCustomerImpact(context);
            riskScore += customerRisk * 0.15;
            if (customerRisk > 0.75) {
                violations.add("Layer-2: Customer impact - Potential widespread customer complaints");
            }
            
            // Layer 3: Data Sensitivity Validation
            double dataSensitivityRisk = evaluateDataSensitivity(context);
            riskScore += dataSensitivityRisk * 0.12;
            if (dataSensitivityRisk > 0.7) {
                violations.add("Layer-3: Data sensitivity - Risk to sensitive customer data");
            }
            
            // Layer 4: Transaction Consistency Checks
            double transactionRisk = evaluateTransactionConsistency(context);
            riskScore += transactionRisk * 0.12;
            if (transactionRisk > 0.7) {
                violations.add("Layer-4: Transaction consistency - Potential data integrity issues");
            }
            
            // Layer 5: Backup/Recovery Availability
            double recoveryRisk = evaluateBackupRecovery(context);
            riskScore += recoveryRisk * 0.10;
            if (recoveryRisk > 0.8) {
                violations.add("Layer-5: Backup/recovery - Limited recovery options available");
            }
            
            // Layer 6: Change Window Validation
            double changeWindowRisk = evaluateChangeWindow(context);
            riskScore += changeWindowRisk * 0.10;
            if (changeWindowRisk > 0.7) {
                violations.add("Layer-6: Change window - Action outside approved change window");
            }
            
            // Layer 7: System Health Metrics
            double healthRisk = evaluateSystemHealth(context);
            riskScore += healthRisk * 0.12;
            if (healthRisk > 0.8) {
                violations.add("Layer-7: System health - System health metrics degraded");
            }
            
            // Layer 8: Current Deployments Check
            double deploymentRisk = evaluateCurrentDeployments(context);
            riskScore += deploymentRisk * 0.08;
            if (deploymentRisk > 0.7) {
                violations.add("Layer-8: Deployments - Critical deployments in progress");
            }
            
            // Layer 9: Recent Incident Frequency
            double incidentFrequencyRisk = evaluateIncidentFrequency(context);
            riskScore += incidentFrequencyRisk * 0.06;
            if (incidentFrequencyRisk > 0.8) {
                violations.add("Layer-9: Incident frequency - Frequent incidents in last hour");
            }
            
            // Clamp risk score to [0.0, 1.0]
            riskScore = Math.min(1.0, riskScore);
            context.setRiskScore(riskScore);
            
            // P1 severity override: Always escalate if high risk
            if ("P1".equalsIgnoreCase(context.getIncident().getSeverity()) && riskScore > HIGH_RISK_THRESHOLD) {
                violations.add("P1 Severity + High Risk: Forcing HITL approval");
                context.setDecision("HITL_REQUIRED");
            }
            
            // Set guardrails
            context.setGuardrailsTriggered(!violations.isEmpty());
            context.setGuardRailViolations(violations);
            
            // Decision logic based on risk and guardrails
            if (!violations.isEmpty()) {
                if (riskScore >= HIGH_RISK_THRESHOLD) {
                    context.setDecision("ESCALATE_TO_HUMAN");
                    logWarning(context, "High-risk incident: " + violations.size() + " guardrail violations");
                } else if (riskScore >= MEDIUM_RISK_THRESHOLD) {
                    context.setDecision("HITL_REQUIRED");
                    logWarning(context, "Medium-risk incident: " + violations.size() + " guardrail violations");
                }
            }
            
            // Build risk factors string
            String riskFactors = buildRiskFactorsString(violations, riskScore);
            context.setRiskFactors(riskFactors);
            
            logExecution(context, "Risk evaluation completed: Risk=" + String.format("%.2f", riskScore) + 
                                 ", Violations=" + violations.size() + ", Decision=" + context.getDecision());
            
            return context;
        } catch (Exception e) {
            handleException(context, e, "risk evaluation");
            return context;
        }
    }

    @Override
    public boolean canExecute(AgentContext context) {
        return context.getDecision() != null;
    }

    @Override
    public int getPriority() {
        return 5; // Runs after confidence scorer
    }

    /**
     * Layer 1: Evaluate production environment risk.
     */
    private double evaluateProductionRisk(AgentContext context) {
        // Check if incident is critical category
        if ("DATABASE".equalsIgnoreCase(context.getIncident().getCategory()) ||
            "NETWORK".equalsIgnoreCase(context.getIncident().getCategory())) {
            return 0.8; // High risk
        }
        return 0.3; // Low risk for other categories
    }

    /**
     * Layer 2: Evaluate customer impact.
     */
    private double evaluateCustomerImpact(AgentContext context) {
        double impact = 0.3; // Base impact
        
        // P1 incidents have high customer impact
        if ("P1".equalsIgnoreCase(context.getIncident().getSeverity())) {
            impact += 0.35;
        } else if ("P2".equalsIgnoreCase(context.getIncident().getSeverity())) {
            impact += 0.20;
        }
        
        // Check if incident affects core services
        if (context.getIncident().getTitle() != null) {
            String title = context.getIncident().getTitle().toLowerCase();
            if (title.contains("payment") || title.contains("checkout") || title.contains("login")) {
                impact += 0.25;
            }
        }
        
        return Math.min(1.0, impact);
    }

    /**
     * Layer 3: Evaluate data sensitivity.
     */
    private double evaluateDataSensitivity(AgentContext context) {
        double sensitivity = 0.3; // Base risk
        
        // Check incident description/title for sensitive keywords
        String description = context.getIncident().getDescription() != null ? 
                            context.getIncident().getDescription().toLowerCase() : "";
        String title = context.getIncident().getTitle() != null ? 
                      context.getIncident().getTitle().toLowerCase() : "";
        
        if (description.contains("payment") || description.contains("pii") || 
            description.contains("billing") || title.contains("payment")) {
            sensitivity = 0.85; // Very high sensitivity
        } else if (description.contains("user_data") || description.contains("account") ||
                   title.contains("user")) {
            sensitivity = 0.70; // High sensitivity
        }
        
        return Math.min(1.0, sensitivity);
    }

    /**
     * Layer 4: Evaluate transaction consistency.
     */
    private double evaluateTransactionConsistency(AgentContext context) {
        double txRisk = 0.3; // Base risk
        
        // Check if incident is related to transaction processing
        if (context.getIncident().getCategory() != null) {
            String category = context.getIncident().getCategory().toLowerCase();
            if (category.contains("database") || category.contains("transaction")) {
                txRisk = 0.75; // High risk
            }
        }
        
        // If no pattern matcher match, increase risk
        if (context.getMatchedPatternId() == null) {
            txRisk += 0.15;
        }
        
        return Math.min(1.0, txRisk);
    }

    /**
     * Layer 5: Evaluate backup and recovery availability.
     */
    private double evaluateBackupRecovery(AgentContext context) {
        // In real scenario, query backup status from monitoring system
        // For now, default to low risk - backup systems typically available
        double recoveryRisk = 0.2;
        
        // If incident is critical database issue, assume high recovery difficulty
        if ("DATABASE".equalsIgnoreCase(context.getIncident().getCategory())) {
            recoveryRisk = 0.7;
        }
        
        return recoveryRisk;
    }

    /**
     * Layer 6: Evaluate change window validity.
     */
    private double evaluateChangeWindow(AgentContext context) {
        LocalDateTime now = LocalDateTime.now();
        
        // Example change window: Mon-Thu 00:00-06:00, Fri-Sun blocked
        int dayOfWeek = now.getDayOfWeek().getValue(); // 1=Monday, 7=Sunday
        int hour = now.getHour();
        
        // No changes allowed on Friday, Saturday, Sunday
        if (dayOfWeek >= 5) {
            return 0.85; // High risk
        }
        
        // Allow changes 00:00-06:00 on Mon-Thu (business hours preparation)
        if (hour >= 0 && hour < 6) {
            return 0.2; // Low risk - within change window
        }
        
        // Outside change window
        return 0.6; // Medium risk outside normal window
    }

    /**
     * Layer 7: Evaluate system health.
     */
    private double evaluateSystemHealth(AgentContext context) {
        // In real scenario, query Prometheus/monitoring for health
        // Use confidence score as proxy for system health
        double confidenceScore = context.getFinalConfidenceScore() != null ? 
                                 context.getFinalConfidenceScore() : 0.8;
        
        // Risk is inverse of confidence (high confidence = healthy system)
        double healthRisk = 1.0 - confidenceScore;
        
        return Math.min(1.0, healthRisk);
    }

    /**
     * Layer 8: Evaluate current deployments.
     */
    private double evaluateCurrentDeployments(AgentContext context) {
        // In real scenario, query deployment tracking system
        // For now, assume no critical deployments
        return 0.2; // Low risk
    }

    /**
     * Layer 9: Evaluate recent incident frequency.
     */
    private double evaluateIncidentFrequency(AgentContext context) {
        // In real scenario, query recent incidents in last hour
        // For now, assume baseline frequency
        
        return 0.3; // Low risk - baseline
    }

    /**
     * Build human-readable risk factors string.
     */
    private String buildRiskFactorsString(List<String> violations, double riskScore) {
        if (violations.isEmpty()) {
            return "No guardrail violations detected. Risk score: " + String.format("%.2f", riskScore);
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("Risk Score: ").append(String.format("%.2f", riskScore)).append("\n");
        sb.append("Guardrail Violations (").append(violations.size()).append("):\n");
        
        for (int i = 0; i < violations.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(violations.get(i)).append("\n");
        }
        
        return sb.toString();
    }
}
